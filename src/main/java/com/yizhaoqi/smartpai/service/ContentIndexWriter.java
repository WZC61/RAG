package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Serialize the final ES bulk with ACL reconciliation/cleanup, after all model calls finish. */
@Service
public class ContentIndexWriter {
    private final FileContentRepository contents;
    private final FileUploadRepository files;
    private final ElasticsearchService search;
    public ContentIndexWriter(FileContentRepository contents, FileUploadRepository files, ElasticsearchService search) {
        this.contents = contents; this.files = files; this.search = search;
    }
    @Transactional(transactionManager = "transactionManager")
    public void write(String md5, long generation, List<EsDocument> documents) {
        FileContent content = contents.findForUpdate(md5).orElseThrow();
        if (content.getDeletedAt() != null || content.getProcessingGeneration() != generation
                || content.getProcessingStatus() != FileContent.ProcessingStatus.PARSED)
            throw new IllegalStateException("Content is no longer the current PARSED generation");
        var relations = files.findAllByFileMd5(md5);
        if (relations.isEmpty()) throw new IllegalStateException("Content has no file references");
        ContentAcl acl = ContentAcl.from(relations);
        documents.forEach(acl::apply);
        search.bulkIndex(documents);
    }
}
