package com.yizhaoqi.smartpai.entity;


import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Elasticsearch存储的文档实体类
 * 包含文档内容和权限信息
 */
@Data
// Existing indices may contain legacy metadata such as contentType. ACL fields
// remain explicit; an extra source field must not break the entire scoped recall.
@JsonIgnoreProperties(ignoreUnknown = true)
public class EsDocument {
    public enum DocumentType { TEXT, FIGURE }

    private String id;             // 文档唯一标识
    private String fileMd5;        // 文件指纹
    private Integer chunkId;       // 文本分块序号
    private String textContent;    // 文本内容
    private Integer pageNumber;    // PDF 页码
    private String anchorText;     // 页内定位锚点
    private float[] vector;        // 维度由当前 Embedding provider 配置决定
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    // Keep the existing query wire name explicit; accept historical isPublic on reads.
    @JsonProperty("public")
    @JsonAlias("isPublic")
    private boolean isPublic;

    private DocumentType documentType;
    private Long processingGeneration; // null only for legacy entry points
    private Integer figureIndex;
    private String figureLabel;
    private String imagePath;      // stable MinIO object key, never a signed URL
    private List<Double> bbox;
    private String caption;
    private String description;
    private String ocrText;
    private String nearbyText;
    // userId/orgTag remain legacy metadata; authorization uses this complete union instead.
    private List<String> allowedUserIds;
    private List<String> allowedOrgTags;

    /**
     * 默认构造函数，用于Jackson反序列化
     */
    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion, 
                     String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.documentType = DocumentType.TEXT;
    }
    

}
