package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.service.SharedContentAclService;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.parsing.DocumentParsingService;
import com.yizhaoqi.smartpai.parsing.NonPdfDocumentParsingService;
import com.yizhaoqi.smartpai.parsing.PdfSignature;
import com.yizhaoqi.smartpai.parsing.description.FigureDescriptionService;
import com.yizhaoqi.smartpai.parsing.persistence.LegacyPermissionContext;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import com.yizhaoqi.smartpai.utils.UrlLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final DocumentService documentService;
    private final ConnectionFactory connections;
    @Autowired
    private KafkaConfig kafkaConfig;
    @Autowired
    private FileContentProcessingService contentProcessing;
    @Autowired
    private FileUploadRepository files;
    @Autowired
    private DocumentParsingService parsingService;
    @org.springframework.beans.factory.annotation.Autowired
    private NonPdfDocumentParsingService nonPdfParsingService;
    @Autowired
    private FigureDescriptionService figureDescriptions;
    @Autowired
    private SharedContentAclService contentAcl;


    @Autowired
    public FileProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            DocumentService documentService
    ) {
        this(parseService, vectorizationService, documentService,
                url -> (HttpURLConnection) url.openConnection());
    }

    FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService,
                           DocumentService documentService, ConnectionFactory connections) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.documentService = documentService;
        this.connections = connections;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        if (task == null) throw new IllegalArgumentException("Missing file processing task");
        if (FileProcessingTask.TASK_TYPE_ACL_CHANGED.equals(task.getTaskType())) {
            if (!task.hasValidAclIdentity()) throw new IllegalArgumentException("Invalid ACL event identity");
            try { contentAcl.reconcile(task.getFileMd5(), task.getUserId()); }
            catch (Exception failure) { throw new IllegalStateException("ACL reconciliation failed", UrlLogSanitizer.exception(failure)); }
            return;
        }
        log.info("Received task: eventId={}, fileMd5={}, processingGeneration={}, taskType={}, requesterId={}",
                UrlLogSanitizer.redact(task.getEventId()), UrlLogSanitizer.redact(task.getFileMd5()),
                task.getProcessingGeneration(), UrlLogSanitizer.redact(task.getTaskType()),
                UrlLogSanitizer.redact(task.getRequesterId()));
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                UrlLogSanitizer.redact(task.getUserId()), UrlLogSanitizer.redact(task.getOrgTag()), task.isPublic());

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
            if (FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS.equals(task.getTaskType())
                    && documentService.isLegacyUploadSuperseded(task.getFileMd5())) {
                log.info("Skipping superseded legacy upload task: fileMd5={}",
                        UrlLogSanitizer.redact(task.getFileMd5()));
                return;
            }
            documentService.markVectorizationProcessing(task.getFileMd5(), false);
        }

        if (FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
            processReindexTask(task);
            return;
        }

        InputStream fileStream = null;
        try {
            // Temporary metadata/quota adapter. ES permissions are aggregated independently.
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
                log.info("Downloading merged object: fileMd5={}, objectPath={}",
                        UrlLogSanitizer.redact(task.getFileMd5()), UrlLogSanitizer.redact(task.getObjectPath()));
                fileStream = downloadFileFromStorage(task.getFilePath());
                if (fileStream == null) throw new IOException("流为空");
                if (!fileStream.markSupported()) fileStream = new BufferedInputStream(fileStream);
                if (contentTask) {
                    LegacyPermissionContext permissions = new LegacyPermissionContext(legacyUser, legacyOrg, legacyPublic);
                    boolean committed = PdfSignature.isPdf(fileStream)
                            ? parsingService.parseAndPersist(task.getFileMd5(), generation, fileStream, permissions)
                            : nonPdfParsingService.parseAndPersist(task.getFileMd5(), generation, fileStream, permissions);
                    // Atomic persistence alone owns PARSED. A false result can mean that another
                    // task committed, or that this generation became stale while PP was running.
                    FileContent.ProcessingStatus current = contentProcessing.checkpoint(task.getFileMd5(), generation);
                    if (current == null || current == FileContent.ProcessingStatus.INDEXED
                            || current == FileContent.ProcessingStatus.FAILED) {
                        log.info("解析任务已过期或终止，跳过向量化，fileMd5: {}, generation: {}, committed: {}",
                                task.getFileMd5(), generation, committed);
                        return;
                    }
                    if (current != FileContent.ProcessingStatus.PARSED)
                        throw new IllegalStateException("Parsed artifact persistence did not reach PARSED");
                } else {
                    // Only legacy messages without a generation retain incremental persistence.
                    parseService.parseAndSave(task.getFileMd5(), fileStream, legacyUser, legacyOrg, legacyPublic);
                }
                log.info("文件解析完成，fileMd5: {}", task.getFileMd5());
            }

            // Descriptions are durable per-Figure artifacts. PARSED retries reuse them,
            // then regenerate embeddings and overwrite the same TEXT/FIGURE ES identities.
            if (contentTask) {
                if (!figureDescriptions.describe(task.getFileMd5(), generation)) return;
                if (contentProcessing.checkpoint(task.getFileMd5(), generation) != FileContent.ProcessingStatus.PARSED)
                    return;
            }

            // 向量化处理
            VectorizationService.VectorizationUsageResult vectorizationResult = contentTask
                    ? vectorizationService.vectorizeWithUsage(task.getFileMd5(), generation,
                            legacyUser, legacyOrg, legacyPublic, task.getRequesterId())
                    : vectorizationService.vectorizeWithUsage(task.getFileMd5(),
                            legacyUser, legacyOrg, legacyPublic, task.getUserId());
            if (contentTask) contentProcessing.indexed(task.getFileMd5(), generation, vectorizationResult);
            else documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());
        } catch (Exception e) {
            Throwable safeFailure = UrlLogSanitizer.exception(e);
            if (contentTask) {
                try {
                    contentProcessing.recordError(task.getFileMd5(), generation, safeFailure);
                } catch (Exception persistenceFailure) {
                    safeFailure.addSuppressed(UrlLogSanitizer.exception(persistenceFailure));
                }
            } else documentService.markVectorizationFailed(task.getFileMd5(), safeFailure);
            log.error("Error processing task: eventId={}, fileMd5={}, generation={}",
                    UrlLogSanitizer.redact(task.getEventId()), UrlLogSanitizer.redact(task.getFileMd5()), generation, safeFailure);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", safeFailure);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", UrlLogSanitizer.exception(e));
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
            Throwable safeFailure = UrlLogSanitizer.exception(e);
            documentService.markVectorizationFailed(task.getFileMd5(), safeFailure);
            log.error("Error reindexing task: eventId={}, fileMd5={}",
                    UrlLogSanitizer.redact(task.getEventId()), UrlLogSanitizer.redact(task.getFileMd5()), safeFailure);
            throw new RuntimeException("Error reindexing task", safeFailure);
        }
    }

    /**
     * 下载原始文件；成功 HTTP 流关闭时释放连接，失败时立即释放。
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    InputStream downloadFileFromStorage(String filePath) throws IOException {
        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                URL url = new URL(filePath);
                HttpURLConnection connection = connections.open(url);
                return openResponse(connection);
            }

            throw new IllegalArgumentException("Unsupported file path format");
        } catch (Exception e) {
            throw new IOException("Failed to download merged file", UrlLogSanitizer.exception(e));
        }
    }

    private InputStream openResponse(HttpURLConnection connection) throws IOException {
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000); // 连接超时30秒
            connection.setReadTimeout(180000);   // 读取超时时间3分钟

            connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                InputStream response = connection.getInputStream();
                if (response == null) throw new IOException("Merged file response stream is missing");
                return new FilterInputStream(response) {
                    private boolean closed;
                    @Override public void close() throws IOException {
                        if (closed) return;
                        closed = true;
                        try { super.close(); }
                        finally { connection.disconnect(); }
                    }
                };
            } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new IOException("Access forbidden - the presigned URL may have expired");
            } else {
                throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
            }
        } catch (Exception e) {
            try (InputStream error = connection.getErrorStream()) {
                // Closing any available error body releases the failed response.
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            } finally {
                connection.disconnect();
            }
            throw new IOException("Merged file HTTP download failed", UrlLogSanitizer.exception(e));
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

}
