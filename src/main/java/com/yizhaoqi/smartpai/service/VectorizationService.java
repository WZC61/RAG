package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.ContentAcl;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.utils.EsDocumentIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;
    @Autowired
    private DocumentFigureRepository documentFigureRepository;
    @Autowired
    private FileContentRepository fileContentRepository;
    @Autowired
    private ContentIndexWriter contentIndexWriter;
    @Autowired
    private FileUploadRepository fileUploadRepository;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        return vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId, null);
    }

    /** Content tasks must pass their real generation; legacy entry points retain their signatures. */
    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, long processingGeneration,
                                                       String userId, String orgTag, boolean isPublic,
                                                       String requesterId) {
        if (processingGeneration < 1) {
            throw new IllegalArgumentException("Content vectorization requires a positive processingGeneration");
        }
        return indexContent(fileMd5, processingGeneration, userId, orgTag, isPublic, requesterId);
    }

    /** Content-only path; legacy UPLOAD_PROCESS/REINDEX remain text-only below. */
    private VectorizationUsageResult indexContent(String md5, long generation, String userId,
                                                  String orgTag, boolean isPublic, String requesterId) {
        requireCurrentParsed(md5, generation);
        List<TextChunk> chunks = fetchTextChunks(md5).stream()
                .filter(chunk -> chunk.getContent() != null && !chunk.getContent().isBlank()).toList();
        List<DocumentFigure> figures = documentFigureRepository
                .findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(md5, generation);
        List<String> figureTexts = figures.stream().map(figure -> figureEmbeddingText(md5, generation, figure)).toList();
        if (chunks.isEmpty() && figures.isEmpty())
            throw new IllegalStateException("No indexable Text or Figure content for current generation");

        // Calls are outside MySQL transactions; an ES write starts only after both sets succeed.
        EmbeddingClient.EmbeddingUsageResult textResult = chunks.isEmpty() ? null : embeddingClient.embedWithUsage(
                chunks.stream().map(TextChunk::getContent).toList(), requesterId, EmbeddingClient.UsageType.UPLOAD);
        EmbeddingClient.EmbeddingUsageResult figureResult = figures.isEmpty() ? null : embeddingClient.embedWithUsage(
                figureTexts, requesterId, EmbeddingClient.UsageType.UPLOAD);
        if (textResult != null && figureResult != null && !textResult.modelVersion().equals(figureResult.modelVersion()))
            throw new IllegalStateException("Embedding provider changed between Text and Figure batches");

        List<EsDocument> docs = new ArrayList<>();
        if (textResult != null) {
            for (int i = 0; i < chunks.size(); i++) {
                TextChunk chunk = chunks.get(i);
                EsDocument doc = new EsDocument(EsDocumentIds.text(md5, generation, chunk.getChunkId()), md5,
                        chunk.getChunkId(), chunk.getContent(), chunk.getPageNumber(), chunk.getAnchorText(),
                        textResult.vectors().get(i), textResult.modelVersion(), userId, orgTag, isPublic);
                doc.setProcessingGeneration(generation);
                docs.add(doc);
            }
        }
        if (figureResult != null) {
            for (int i = 0; i < figures.size(); i++) {
                DocumentFigure figure = figures.get(i);
                EsDocument doc = new EsDocument();
                doc.setId(EsDocumentIds.figure(md5, generation, figure.getPageNumber(), figure.getFigureIndex()));
                doc.setDocumentType(EsDocument.DocumentType.FIGURE);
                doc.setFileMd5(md5);
                doc.setProcessingGeneration(generation);
                doc.setPageNumber(figure.getPageNumber());
                doc.setFigureIndex(figure.getFigureIndex());
                doc.setFigureLabel(figure.getFigureLabel());
                doc.setImagePath(figure.getImagePath());
                doc.setBbox(readBbox(figure.getBbox()));
                doc.setCaption(figure.getCaption());
                doc.setDescription(figure.getDescription());
                doc.setOcrText(figure.getOcrText());
                doc.setNearbyText(figure.getNearbyText());
                doc.setTextContent(figureTexts.get(i));
                doc.setVector(figureResult.vectors().get(i));
                doc.setModelVersion(figureResult.modelVersion());
                doc.setUserId(userId);
                doc.setOrgTag(orgTag);
                doc.setPublic(isPublic);
                docs.add(doc);
            }
        }
        // Detect stale tasks after potentially slow model calls, before external index writes.
        requireCurrentParsed(md5, generation);
        contentIndexWriter.write(md5, generation, docs);
        int tokens = Math.addExact(textResult == null ? 0 : textResult.totalTokens(),
                figureResult == null ? 0 : figureResult.totalTokens());
        String model = textResult != null ? textResult.modelVersion() : figureResult.modelVersion();
        // actualChunkCount now counts all successfully indexed Text + Figure units for content tasks.
        return new VectorizationUsageResult(tokens, docs.size(), model);
    }

    private void requireCurrentParsed(String md5, long generation) {
        FileContent content = fileContentRepository.findByFileMd5(md5).orElseThrow();
        if (content.getProcessingGeneration() != generation || content.getProcessingStatus() != FileContent.ProcessingStatus.PARSED)
            throw new IllegalStateException("Content generation is no longer current PARSED");
    }

    private String figureEmbeddingText(String md5, long generation, DocumentFigure figure) {
        if (!md5.equals(figure.getFileMd5()) || !Long.valueOf(generation).equals(figure.getProcessingGeneration())
                || figure.getPageNumber() == null || figure.getPageNumber() < 1
                || figure.getFigureIndex() == null || figure.getFigureIndex() < 1
                || figure.getImagePath() == null || figure.getImagePath().isBlank())
            throw new IllegalStateException("Incomplete Figure index identity");
        if (figure.getDescription() == null || figure.getDescription().isBlank())
            throw new IllegalStateException("Figure description must be ready before indexing");
        return Stream.of(figure.getCaption(), figure.getDescription(), figure.getOcrText())
                .filter(value -> value != null && !value.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private List<Double> readBbox(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            // The PP mapper deliberately represents unknown coordinates as an empty array.
            if (node.isArray() && node.isEmpty()) return List.of();
            if (!node.isArray() || node.size() != 4) throw new IllegalArgumentException();
            List<Double> coordinates = new ArrayList<>();
            for (JsonNode coordinate : node) {
                if (!coordinate.isNumber() || !Double.isFinite(coordinate.doubleValue())) throw new IllegalArgumentException();
                coordinates.add(coordinate.doubleValue());
            }
            return coordinates;
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid Figure bbox", failure);
        }
    }

    private VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag,
                                                        boolean isPublic, String requesterId, Long generation) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            generation == null
                                    ? EsDocumentIds.legacyText(fileMd5, userId, chunks.get(i).getChunkId())
                                    : EsDocumentIds.text(fileMd5, generation, chunks.get(i).getChunkId()),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            chunks.get(i).getPageNumber(),
                            chunks.get(i).getAnchorText(),
                            vectors.get(i),
                            embeddingResult.modelVersion(),
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            ContentAcl acl = ContentAcl.from(
                    fileUploadRepository.findAllByFileMd5(fileMd5));
            esDocuments.forEach(acl::apply);
            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            logger.info("向量化完成，fileMd5: {}", fileMd5);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getPageNumber(),
                        vector.getAnchorText()
                ))
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
