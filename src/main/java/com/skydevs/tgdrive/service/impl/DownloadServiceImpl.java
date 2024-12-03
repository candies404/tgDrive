package com.skydevs.tgdrive.service.impl;

import com.alibaba.fastjson.JSON;
import com.pengrad.telegrambot.model.File;
import com.skydevs.tgdrive.entity.BigFileInfo;
import com.skydevs.tgdrive.mapper.FileMapper;
import com.skydevs.tgdrive.service.BotService;
import com.skydevs.tgdrive.service.DownloadService;
import com.skydevs.tgdrive.utils.OkHttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class DownloadServiceImpl implements DownloadService {

    @Autowired
    private BotService botService;
    @Autowired
    private FileMapper fileMapper;

    private final OkHttpClient okHttpClient = OkHttpClientFactory.createClient();

    @Override
    public ResponseEntity<StreamingResponseBody> downloadFile(String fileID) {
        try (InputStream inputStream = downloadFileInputStream(fileID);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()){
            byte[] data = new byte[8192];
            int byteRead;
            while ((byteRead = inputStream.read(data)) != -1) {
                buffer.write(data, 0, byteRead);
            }

            byte[] inputData = buffer.toByteArray();
            try (InputStream inputStream1 = new ByteArrayInputStream(inputData);
            InputStream inputStream2 = new ByteArrayInputStream(inputData)) {
                BigFileInfo record = parseBigFileInfo(inputStream1);

                if (record != null && record.isRecordFile()) {
                    return handleRecordFile(fileID, record);
                }
                return handleRegularFile(fileID, inputStream2);
            }
        } catch (Exception e) {
            log.error("下载文件失败：" + e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private ResponseEntity<StreamingResponseBody> handleRegularFile(String fileID, InputStream inputStream) {
        log.info("文件不是记录文件，直接下载文件...");

        File file = botService.getFile(fileID);
        String filename = resolveFilename(fileID, file.filePath());
        Long fullSize = fileMapper.getFullSizeByFileId(fileID);
        if (fullSize == null || fullSize == 0) {
            fullSize = file.fileSize();
        }

        HttpHeaders headers = setHeaders(filename, fullSize);

        StreamingResponseBody streamingResponseBody = outputStream -> {
            streamData(inputStream, outputStream);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(getContentTypeFromFilename(filename)))
                .body(streamingResponseBody);
    }

    private void streamData(InputStream inputStream, OutputStream outputStream) {
        try (InputStream is = inputStream) {
            byte[] buffer = new byte[4096];
            int byteRead;
            while ((byteRead = is.read(buffer)) != -1) {
                outputStream.write(buffer, 0, byteRead);
            }
        } catch (IOException e) {
            handleClientAbortException(e);
        } catch (Exception e) {
            log.info("文件下载终止");
            log.info(e.getMessage(), e);
        }
    }

    private ResponseEntity<StreamingResponseBody> handleRecordFile(String fileID, BigFileInfo record) {
        log.info("文件名为：" + record.getFileName());
        log.info("检测到记录文件，开始下载并合并分片文件...");

        String filename = resolveFilename(fileID, record.getFileName());
        Long fullSize = fileMapper.getFullSizeByFileId(fileID);

        HttpHeaders headers = setHeaders(filename, fullSize);

        List<String> partFileIds = record.getFileIds();

        StreamingResponseBody streamingResponseBody = outputStream -> {
            downloadAndMergeFileParts(partFileIds, outputStream);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(getContentTypeFromFilename(filename)))
                .body(streamingResponseBody);
    }

    private void downloadAndMergeFileParts(List<String> partFileIds, OutputStream outputStream) {
        int maxConcurrentDownloads = 3; // 最大并发下载数
        ExecutorService executorService = Executors.newFixedThreadPool(maxConcurrentDownloads);

        List<PipedInputStream> pipedInputStreams = new ArrayList<>(partFileIds.size());
        CountDownLatch latch = new CountDownLatch(partFileIds.size());

        try {
            for (int i = 0; i < partFileIds.size(); i++) {
                pipedInputStreams.add(new PipedInputStream());
            }

            for (int i = 0; i < partFileIds.size(); i++) {
                final int index = i;
                final String partFileId = partFileIds.get(i);
                final PipedInputStream pipedInputStream = pipedInputStreams.get(index);
                final PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);

                executorService.submit(() -> {
                    try (InputStream partInputStream = downloadFileByte(partFileId).byteStream();
                         OutputStream pos = pipedOutputStream) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = partInputStream.read(buffer)) != -1) {
                            pos.write(buffer, 0, bytesRead);
                            pos.flush();
                        }
                    } catch (IOException e) {
                        log.error("分片文件下载失败：{}", partFileId, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            for (int i = 0; i < partFileIds.size(); i++) {
                try (InputStream pis = pipedInputStreams.get(i)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = pis.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    handleClientAbortException(e);
                }
            }

            latch.await();
        } catch (Exception e) {
            log.error("文件下载终止：{}", e.getMessage(), e);
        } finally {
            executorService.shutdown();
        }
    }

    private void handleClientAbortException(IOException e) {
        String message = e.getMessage();
        if (message != null && (message.contains("An established connection was aborted") || message.contains("你的主机中的软件中止了一个已建立的连接"))) {
            log.info("客户端中止了连接：{}", message);
        } else {
            log.error("写入输出流时发生 IOException", e);
            throw new RuntimeException(e);
        }
    }

    private String resolveFilename(String fileID, String defaultName) {
        String filename = fileMapper.getFileNameByFileId(fileID);
        if (filename == null) {
            filename = defaultName;
        }
        // 上传到tg的gif会被转换为MP4
        if (filename.endsWith(".gif")) {
            filename = filename.substring(0, filename.length() - 4) + ".mp4";
        }
        return filename;
    }

    private BigFileInfo parseBigFileInfo(InputStream inputStream) {
        try {
            String fileContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return JSON.parseObject(fileContent, BigFileInfo.class);
        } catch (Exception e) {
            log.info("文件不是 BigFileInfo类型，作为普通文件处理");
            return null;
        }
    }

    private InputStream downloadFileInputStream(String fileID) throws IOException {
        File file = botService.getFile(fileID);
        String fileUrl = botService.getFullDownloadPath(file);

        Request request = new Request.Builder()
                .url(fileUrl)
                .get()
                .build();

        Response response = okHttpClient.newCall(request).execute();

        if (!response.isSuccessful()) {
            log.error("无法下载文件，响应码：" + response.code());
            throw new IOException("无法下载文件，响应码：" + response.code());
        }

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            log.error("响应体为空");
            throw new IOException("响应体为空");
        }

        return responseBody.byteStream();
    }

    /**
     * 设置响应头
     *
     * @param filename
     * @param size
     * @return
     */
    private HttpHeaders setHeaders(String filename, Long size) {
        HttpHeaders headers = new HttpHeaders();
        try {
            String contentType = getContentTypeFromFilename(filename);
            headers.setContentType(MediaType.parseMediaType(contentType));
            if (size != null && size > 0) {
                headers.setContentLength(size);
            }

            if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
                // 对于图片和视频，设置 Content-Disposition 为 inline
                headers.setContentDisposition(ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build());
            } else {
                // 使用 URLEncoder 编码文件名，确保支持中文
                String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString()).replace("+", "%20");
                String contentDisposition = "attachment; filename*=UTF-8''" + encodedFilename;
                headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
            }
        } catch (UnsupportedEncodingException e) {
            log.error("不支持的编码");
        }
        return headers;
    }

    /**
     * 下载分片文件
     *
     * @param partFileId
     * @return
     * @throws IOException
     */
    private ResponseBody downloadFileByte(String partFileId) throws IOException {
        File partFile = botService.getFile(partFileId);
        String partFileUrl = botService.getFullDownloadPath(partFile);
        Request partRequest = new Request.Builder()
                .url(partFileUrl)
                .get()
                .build();

        Response response = okHttpClient.newCall(partRequest).execute();
        if (!response.isSuccessful()) {
            log.error("无法下载分片文件，响应码：" + response.code());
            throw new IOException("无法下载分片文件，响应码：" + response.code());
        }

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            log.error("分片响应体为空");
            throw new IOException("分片响应体为空");
        }

        return responseBody;
    }

    /**
     * 获取文件类型
     *
     * @param filename
     * @return
     */
    private String getContentTypeFromFilename(String filename) {
        String contentType = null;
        Path path = Paths.get(filename);
        try {
            contentType = Files.probeContentType(path);
        } catch (IOException e) {
            log.warn("无法通过 Files.probeContentType 获取 MIME 类型: " + e.getMessage());
        }

        if (contentType == null) {
            // 手动映射常见的文件扩展名到 MIME 类型
            String extension = getFileExtension(filename).toLowerCase();
            switch (extension) {
                case "gif":
                    contentType = "image/gif";
                    break;
                case "jpg":
                case "jpeg":
                    contentType = "image/jpeg";
                    break;
                case "png":
                    contentType = "image/png";
                    break;
                case "bmp":
                    contentType = "image/bmp";
                    break;
                case "txt":
                    contentType = "text/plain";
                    break;
                case "pdf":
                    contentType = "application/pdf";
                    break;
                case "mp4":
                    contentType = "video/mp4";
                    break;
                // 添加其他需要的类型
                default:
                    contentType = "application/octet-stream";
                    break;
            }
        }
        return contentType;
    }

    /**
     * 获取文件扩展名
     *
     * @param filename
     * @return
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}