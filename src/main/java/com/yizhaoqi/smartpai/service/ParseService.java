package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunker;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunkFragment;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);
    private static final String PDF_PARSER_LITEPARSE = "liteparse";
    private static final Pattern LITEPARSE_PAGE_FOOTER_PATTERN = Pattern.compile("(?i)^No\\.\\s*\\d+\\s*/\\s*\\d+%?$");

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TextChunker textChunker;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;
    
    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;
    
    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${file.parsing.pdf.engine:liteparse}")
    private String pdfParsingEngine;

    @Value("${file.parsing.liteparse.command:lit}")
    private String liteParseCommand;

    @Value("${file.parsing.liteparse.ocr-enabled:true}")
    private boolean liteParseOcrEnabled;

    @Value("${file.parsing.liteparse.ocr-language:chi_sim+eng}")
    private String liteParseOcrLanguage;

    @Value("${aliyun.ocr.enabled:false}")
    private boolean aliyunOcrEnabled;

    @Value("${aliyun.ocr.callback-token:}")
    private String aliyunOcrCallbackToken;

    @Value("${server.port:8081}")
    private int serverPort;

    @Value("${server.servlet.context-path:}")
    private String serverContextPath;

    @Value("${file.parsing.liteparse.tessdata-path:}")
    private String liteParseTessdataPath;

    @Value("${file.parsing.liteparse.max-pages:1000}")
    private int liteParseMaxPages;

    @Value("${file.parsing.liteparse.dpi:150}")
    private int liteParseDpi;

    @Value("${file.parsing.liteparse.num-workers:0}")
    private int liteParseNumWorkers;

    @Value("${file.parsing.liteparse.timeout-seconds:300}")
    private long liteParseTimeoutSeconds;
    
    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);
        
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                parsePdfAndSave(fileMd5, bufferedStream, userId, orgTag, isPublic);
                logger.info("PDF 文件 LiteParse 页级解析和入库完成，fileMd5: {}", fileMd5);
                return;
            }

            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
        logger.info("开始估算文档 Embedding Token");
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                return estimatePdfEmbeddingUsage(bufferedStream);
            }

            StreamingEstimateHandler handler = new StreamingEstimateHandler();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(bufferedStream, handler, metadata, context);
            return handler.snapshot();
        } catch (SAXException e) {
            logger.error("文档 Embedding Token 估算失败", e);
            throw new RuntimeException("文档 Embedding Token 估算失败", e);
        }
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsage = (double) usedMemory / maxMemory;
        
        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();
            
            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " + 
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
    
    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            String parentChunkText = buffer.toString();
            logger.debug("处理父文本块，大小: {} bytes", parentChunkText.length());

            // 1. 将父块分割成更小的、有语义的子切片
            List<TextChunkFragment> childChunks = textChunker.chunk(parentChunkText);

            // 2. 将子切片批量保存到数据库
            this.savedChunkCount = ParseService.this.saveChildChunks(
                    fileMd5, childChunks, userId, orgTag, isPublic, this.savedChunkCount, null
            );

            // 3. 清空缓冲区，为下一个父块做准备
            buffer.setLength(0);
        }
    }

    private class StreamingEstimateHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private long estimatedTokens = 0L;
        private int estimatedChunkCount = 0;

        private StreamingEstimateHandler() {
            super(-1);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            List<TextChunkFragment> childChunks = textChunker.chunk(buffer.toString());
            estimatedChunkCount += childChunks.size();
            estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks.stream().map(TextChunkFragment::text).toList());
            buffer.setLength(0);
        }

        private EmbeddingEstimate snapshot() {
            return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
        }
    }

    /**
     * 将子切片列表保存到数据库。
     *
     * @param fileMd5         文件的 MD5 哈希值
     * @param chunks          子切片文本列表
     * @param userId          上传用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param startingChunkId 当前批次的起始分片ID
     * @return 保存后总的分片数量
     */
    private int saveChildChunks(String fileMd5, List<TextChunkFragment> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId, Integer pageNumber) {
        int currentChunkId = startingChunkId;
        for (TextChunkFragment chunk : chunks) {
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk.text());
            vector.setPageNumber(pageNumber);
            vector.setAnchorText(chunk.anchorText());
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库", chunks.size());
        return currentChunkId;
    }

    private void parsePdfAndSave(String fileMd5, InputStream fileStream, String userId, String orgTag, boolean isPublic) throws IOException {
        List<LiteParsePage> pages = parsePdfWithLiteParse(fileStream);
        int savedChunkCount = 0;

        for (LiteParsePage page : pages) {
            String pageText = page.text();
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            List<TextChunkFragment> childChunks = textChunker.chunk(page.pageNumber(), pageText);
            savedChunkCount = saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, savedChunkCount, page.pageNumber());
        }
    }

    private EmbeddingEstimate estimatePdfEmbeddingUsage(InputStream fileStream) throws IOException {
        List<LiteParsePage> pages = parsePdfWithLiteParse(fileStream);
        long estimatedTokens = 0L;
        int estimatedChunkCount = 0;

        for (LiteParsePage page : pages) {
            String pageText = page.text();
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            List<TextChunkFragment> childChunks = textChunker.chunk(page.pageNumber(), pageText);
            estimatedChunkCount += childChunks.size();
            estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks.stream().map(TextChunkFragment::text).toList());
        }

        return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
    }

    private List<LiteParsePage> parsePdfWithLiteParse(InputStream fileStream) throws IOException {
        if (!PDF_PARSER_LITEPARSE.equalsIgnoreCase(pdfParsingEngine)) {
            throw new IOException("不支持的 PDF 解析引擎: " + pdfParsingEngine);
        }

        Path inputPath = Files.createTempFile("paismart-liteparse-", ".pdf");
        Path outputPath = Files.createTempFile("paismart-liteparse-", ".json");
        Path stdoutPath = Files.createTempFile("paismart-liteparse-", ".out");
        Path stderrPath = Files.createTempFile("paismart-liteparse-", ".err");

        try {
            Files.copy(fileStream, inputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            List<String> command = buildLiteParseCommand(inputPath, outputPath);
            logger.info("调用 LiteParse 解析 PDF: command={}", maskLiteParseCommand(command));

            Process process;
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(command)
                        .redirectOutput(stdoutPath.toFile())
                        .redirectError(stderrPath.toFile());
                applyLiteParseEnvironment(processBuilder);
                process = processBuilder.start();
            } catch (IOException e) {
                throw new IOException("启动 LiteParse 失败，请确认已安装 lit 命令或配置 file.parsing.liteparse.command: " + liteParseCommand, e);
            }

            boolean finished;
            try {
                finished = process.waitFor(liteParseTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 LiteParse 解析被中断", e);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("LiteParse 解析超时: " + liteParseTimeoutSeconds + " 秒");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = readProcessLog(stderrPath);
                String stdout = readProcessLog(stdoutPath);
                throw new IOException("LiteParse 解析失败，exitCode=" + exitCode
                        + ", stderr=" + stderr + ", stdout=" + stdout);
            }

            return readLiteParsePages(outputPath);
        } finally {
            deleteQuietly(inputPath);
            deleteQuietly(outputPath);
            deleteQuietly(stdoutPath);
            deleteQuietly(stderrPath);
        }
    }

    private List<String> buildLiteParseCommand(Path inputPath, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(liteParseCommand);
        command.add("parse");
        command.add(inputPath.toString());
        command.add("--format");
        command.add("json");
        command.add("--output");
        command.add(outputPath.toString());
        command.add("--max-pages");
        command.add(String.valueOf(liteParseMaxPages));
        command.add("--dpi");
        command.add(String.valueOf(liteParseDpi));

        if (!liteParseOcrEnabled) {
            command.add("--no-ocr");
        } else {
            command.add("--ocr-language");
            command.add(liteParseOcrLanguage);
            String ocrServerUrl = effectiveLiteParseOcrServerUrl();
            if (hasText(ocrServerUrl)) {
                command.add("--ocr-server-url");
                command.add(ocrServerUrl);
            }
        }

        if (liteParseNumWorkers > 0) {
            command.add("--num-workers");
            command.add(String.valueOf(liteParseNumWorkers));
        }

        command.add("--quiet");
        return command;
    }

    private String effectiveLiteParseOcrServerUrl() {
        if (!aliyunOcrEnabled) {
            return "";
        }

        String contextPath = normalizeContextPath(serverContextPath);
        String url = "http://127.0.0.1:" + serverPort + contextPath + "/api/v1/internal/ocr/liteparse";
        if (hasText(aliyunOcrCallbackToken)) {
            url += "?token=" + URLEncoder.encode(aliyunOcrCallbackToken.trim(), StandardCharsets.UTF_8);
        }
        return url;
    }

    private String normalizeContextPath(String contextPath) {
        if (!hasText(contextPath) || "/".equals(contextPath.trim())) {
            return "";
        }
        String normalized = contextPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void applyLiteParseEnvironment(ProcessBuilder processBuilder) {
        if (hasText(liteParseTessdataPath)) {
            processBuilder.environment().put("TESSDATA_PREFIX", liteParseTessdataPath.trim());
        }
    }

    private List<LiteParsePage> readLiteParsePages(Path outputPath) throws IOException {
        JsonNode root = objectMapper.readTree(outputPath.toFile());
        JsonNode pagesNode = root.path("pages");
        if (!pagesNode.isArray()) {
            throw new IOException("LiteParse 输出缺少 pages 数组");
        }

        List<LiteParsePage> pages = new ArrayList<>();
        for (JsonNode pageNode : pagesNode) {
            int pageNumber = pageNode.path("page").asInt(0);
            if (pageNumber <= 0) {
                pageNumber = pages.size() + 1;
            }

            String pageText = pageNode.path("text").asText("");
            pages.add(new LiteParsePage(pageNumber, normalizeLiteParseText(pageText)));
        }

        return pages;
    }

    private String normalizeLiteParseText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] lines = text
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split("\n", -1);

        List<String> cleanedLines = new ArrayList<>(lines.length);
        boolean previousBlank = true;
        for (String line : lines) {
            String cleanedLine = normalizeLiteParseLine(line);
            if (cleanedLine.isBlank() || shouldSkipLiteParseLine(cleanedLine)) {
                if (!previousBlank) {
                    cleanedLines.add("");
                    previousBlank = true;
                }
                continue;
            }

            cleanedLines.add(cleanedLine);
            previousBlank = false;
        }

        return String.join("\n", cleanedLines)
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String normalizeLiteParseLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }

        String cleaned = line.strip()
                .replaceAll("[ \\t\\x0B\\f]+", " ");

        cleaned = cleaned.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "");
        cleaned = cleaned.replaceAll("(?<=[A-Za-z0-9])\\s+(?=\\p{IsHan})", "");
        cleaned = cleaned.replaceAll("(?<=\\p{IsHan})\\s+(?=[A-Za-z0-9])", "");
        cleaned = cleaned.replaceAll("\\s+([，。！？；：、）】》])", "$1");
        cleaned = cleaned.replaceAll("([，。！？；：、])\\s+(?=\\p{IsHan})", "$1");
        cleaned = cleaned.replaceAll("([（【《])\\s+", "$1");
        return cleaned.trim();
    }

    private boolean shouldSkipLiteParseLine(String line) {
        return LITEPARSE_PAGE_FOOTER_PATTERN.matcher(line).matches();
    }

    private String readProcessLog(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            int maxLength = 2000;
            if (content.length() <= maxLength) {
                return content;
            }
            return content.substring(0, maxLength) + "...";
        } catch (IOException e) {
            return "";
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.debug("删除临时文件失败: {}", path, e);
        }
    }

    private boolean isPdfDocument(BufferedInputStream stream) throws IOException {
        stream.mark(bufferSize);
        byte[] header = stream.readNBytes(5);
        stream.reset();
        return header.length == 5 && "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
    }

    private List<String> maskLiteParseCommand(List<String> command) {
        List<String> masked = new ArrayList<>(command.size());
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (i > 0 && "--password".equals(command.get(i - 1))) {
                masked.add("******");
            } else {
                masked.add(arg);
            }
        }
        return masked;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record LiteParsePage(int pageNumber, String text) {
    }

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}
