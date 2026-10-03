package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final DocumentService documentService;
    @Autowired
    private KafkaConfig kafkaConfig;
    @Autowired
    private FileContentProcessingService contentProcessing;
    @Autowired
    private FileUploadRepository files;


    public FileProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            DocumentService documentService
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.documentService = documentService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        if (task == null) throw new IllegalArgumentException("Missing file processing task");
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        boolean contentTask = FileProcessingTask.TASK_TYPE_PROCESS_CONTENT.equals(task.getTaskType());
        long generation = contentTask && task.getProcessingGeneration() != null ? task.getProcessingGeneration() : 0;
        FileContent.ProcessingStatus checkpoint = null;
        if (contentTask) {
            if (!task.hasValidContentIdentity()) {
                throw new IllegalArgumentException("Invalid content processing identity");
            }
            checkpoint = contentProcessing.checkpoint(task.getFileMd5(), generation);
            if (checkpoint == null || checkpoint == FileContent.ProcessingStatus.INDEXED
                    || checkpoint == FileContent.ProcessingStatus.FAILED) return;
        } else {
            if (!FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS.equals(task.getTaskType())
                    && !FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
                throw new IllegalArgumentException("Unsupported file processing task type");
            }
            documentService.markVectorizationProcessing(task.getFileMd5(), false);
        }

        if (FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
            processReindexTask(task);
            return;
        }

        InputStream fileStream = null;
        try {
            // Temporary adapter for unchanged user-scoped parse/vector APIs. These are not
            // content identity or an aggregate ACL; the ACL model is migrated in a later phase.
            FileUpload legacyAccess = contentTask
                    ? files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(task.getFileMd5(), task.getRequesterId())
                        .or(() -> files.findFirstByFileMd5OrderByCreatedAtDesc(task.getFileMd5())).orElseThrow()
                    : null;
            String legacyUser = contentTask ? legacyAccess.getUserId() : task.getUserId();
            String legacyOrg = contentTask ? legacyAccess.getOrgTag() : task.getOrgTag();
            boolean legacyPublic = contentTask ? legacyAccess.isPublic() : task.isPublic();
            // PARSED is a durable checkpoint: retries need neither download nor parse again.
            // Same-md5 partition ordering is the normal concurrency guard, not a processing lease.
            if (!contentTask || checkpoint == FileContent.ProcessingStatus.MERGED) {
                fileStream = downloadFileFromStorage(task.getFilePath());
                if (fileStream == null) throw new IOException("流为空");
                if (!fileStream.markSupported()) fileStream = new BufferedInputStream(fileStream);
                parseService.parseAndSave(task.getFileMd5(), fileStream, legacyUser, legacyOrg, legacyPublic);
                if (contentTask) contentProcessing.parsed(task.getFileMd5(), generation);
                log.info("文件解析完成，fileMd5: {}", task.getFileMd5());
            }

            // 向量化处理
            VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                    task.getFileMd5(),
                    legacyUser,
                    legacyOrg,
                    legacyPublic,
                    contentTask ? task.getRequesterId() : task.getUserId()
            );
            if (contentTask) contentProcessing.indexed(task.getFileMd5(), generation, vectorizationResult);
            else documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());
        } catch (Exception e) {
            if (contentTask) {
                try {
                    contentProcessing.recordError(task.getFileMd5(), generation, e);
                } catch (Exception persistenceFailure) {
                    e.addSuppressed(persistenceFailure); // Preserve the business failure for Kafka retry.
                }
            } else documentService.markVectorizationFailed(task.getFileMd5(), e);
            log.error("Error processing task: {}", task, e);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
        }
    }

    private void processReindexTask(FileProcessingTask task) {
        try {
            String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                    ? task.getUserId()
                    : task.getRequesterId();
            documentService.reindexDocument(task.getFileMd5(), requesterId);
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(), e);
            log.error("Error reindexing task: {}", task, e);
            throw new RuntimeException("Error reindexing task", e);
        }
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

}
