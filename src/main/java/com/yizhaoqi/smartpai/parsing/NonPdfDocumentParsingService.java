package com.yizhaoqi.smartpai.parsing;

import com.yizhaoqi.smartpai.parsing.persistence.LegacyPermissionContext;
import com.yizhaoqi.smartpai.parsing.persistence.ParsedArtifactPersistenceService;
import com.yizhaoqi.smartpai.service.ParseService;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Tika extraction outside MySQL, followed by the same atomic artifact commit used by PDFs. */
@Service
public class NonPdfDocumentParsingService {
    private final ParseService parser;
    private final ParsedArtifactPersistenceService persistence;

    public NonPdfDocumentParsingService(ParseService parser, ParsedArtifactPersistenceService persistence) {
        this.parser = parser;
        this.persistence = persistence;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.NOT_SUPPORTED)
    public boolean parseAndPersist(String fileMd5, long generation, InputStream input,
                                   LegacyPermissionContext permissions) throws IOException, TikaException {
        var chunks = parser.parseToChunks(input);
        // The separate service's REQUIRES_NEW commit rechecks current MERGED/generation under lock.
        return persistence.persist(fileMd5, generation, chunks, List.of(), permissions);
    }
}
