package com.yizhaoqi.smartpai.entity;

import lombok.Data;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A ranked piece of evidence, independent of chat/citation rendering. */
@Data
public class RetrievalResult {
    public enum Channel { VECTOR, BM25 }

    private String entryId;
    private EsDocument.DocumentType documentType;
    private String fileMd5;
    private Long processingGeneration;
    private String fileName;
    private Integer pageNumber;
    private String textContent;
    private Integer chunkId;
    private String anchorText;
    private Integer figureIndex;
    private String figureLabel;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String imagePath;
    private List<Double> bbox;
    private String caption;
    private String description;
    private String ocrText;
    private boolean isPublic;
    private Integer vectorRank;
    private Integer bm25Rank;
    private Set<Channel> matchedChannels = new LinkedHashSet<>();
    private double rrfScore;
}
