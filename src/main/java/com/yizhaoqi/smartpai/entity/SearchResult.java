package com.yizhaoqi.smartpai.entity;

import lombok.Data;
import java.util.List;
import java.util.Set;

@Data
public class SearchResult {
    private String fileMd5;    // 文件指纹
    private Integer chunkId;   // 文本分块序号
    private String textContent; // 文本内容
    private Double score;      // 搜索得分
    private String fileName;   // 原始文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开
    private Integer pageNumber; // PDF 页码
    private String anchorText; // 页内定位锚点
    private String retrievalMode; // 召回方式
    private String matchedChunkText; // 命中的 chunk 原文
    // Additive compatibility fields; consumers can keep using the original text fields.
    private String entryId;
    private EsDocument.DocumentType documentType;
    private Long processingGeneration;
    private Integer figureIndex;
    private String figureLabel;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String imagePath;
    private List<Double> bbox;
    private String caption;
    private String description;
    private String ocrText;
    private Integer vectorRank;
    private Integer bm25Rank;
    private Set<RetrievalResult.Channel> matchedChannels;
    private Double rrfScore;
    private boolean degraded;
    private Set<RetrievalResult.Channel> failedChannels;

    public static SearchResult fromRetrieval(RetrievalResult r, RetrievalResponse response) {
        SearchResult result = new SearchResult(r.getFileMd5(), r.getChunkId(), r.getTextContent(), r.getRrfScore(),
                null, null, r.isPublic(), r.getFileName(), r.getPageNumber(), r.getAnchorText(),
                response.getRetrievalMode(), r.getTextContent());
        result.setEntryId(r.getEntryId()); result.setDocumentType(r.getDocumentType());
        result.setProcessingGeneration(r.getProcessingGeneration());
        result.setFigureIndex(r.getFigureIndex()); result.setFigureLabel(r.getFigureLabel());
        result.setImagePath(r.getImagePath()); result.setBbox(r.getBbox()); result.setCaption(r.getCaption());
        result.setDescription(r.getDescription()); result.setOcrText(r.getOcrText());
        result.setVectorRank(r.getVectorRank()); result.setBm25Rank(r.getBm25Rank());
        result.setMatchedChannels(r.getMatchedChannels()); result.setRrfScore(r.getRrfScore());
        result.setDegraded(response.isDegraded()); result.setFailedChannels(response.failedChannels());
        return result;
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, score, null, null, false, null, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, score, null, null, false, fileName, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, null, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, pageNumber, anchorText, null, textContent);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
    }
}
