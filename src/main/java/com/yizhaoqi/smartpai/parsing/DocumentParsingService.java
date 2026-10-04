package com.yizhaoqi.smartpai.parsing;

import com.yizhaoqi.smartpai.parsing.chunk.ParsedDocumentChunker;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.parsing.model.DocumentParseResult;
import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.persistence.DocumentParsingPersistenceCoordinator;
import com.yizhaoqi.smartpai.parsing.persistence.LegacyPermissionContext;
import com.yizhaoqi.smartpai.parsing.pp.PpStructureApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** PDF orchestration only; the coordinator owns image preparation and the database commit. */
@Service
public class DocumentParsingService {
    private static final Logger log = LoggerFactory.getLogger(DocumentParsingService.class);
    private final Supplier<Function<Path, DocumentParseResult>> pdfParser;
    private final DocumentParseResultAssembler assembler = new DocumentParseResultAssembler();
    private final ParsedDocumentChunker chunker;
    private final DocumentParsingPersistenceCoordinator persistence;

    @Autowired
    public DocumentParsingService(ObjectProvider<PpStructureApiClient> clients,
                                  ParsedDocumentChunker chunker, DocumentParsingPersistenceCoordinator persistence) {
        this(() -> {
            PpStructureApiClient client = clients.getIfAvailable();
            if (client == null) throw new IllegalStateException("PP-StructureV3 disabled: enable paddle.pp-structure.enabled "
                    + "(PADDLEOCR_ENABLED=true) and configure PADDLEOCR_ACCESS_TOKEN");
            return client::parsePdf;
        }, chunker, persistence);
    }

    // Package-local transport seam: tests never call the real PP service.
    DocumentParsingService(Supplier<Function<Path, DocumentParseResult>> pdfParser,
                           ParsedDocumentChunker chunker, DocumentParsingPersistenceCoordinator persistence) {
        this.pdfParser = Objects.requireNonNull(pdfParser, "pdfParser");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    /** Caller owns the input stream; this method owns and always removes the temporary PDF. */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.NOT_SUPPORTED)
    public boolean parseAndPersist(String fileMd5, long generation, InputStream input,
                                   LegacyPermissionContext permissions) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(permissions, "permissions");
        Function<Path, DocumentParseResult> parser = pdfParser.get();
        Path pdf = Files.createTempFile("paismart-pp-", ".pdf");
        Throwable failure = null;
        try {
            // Files.copy streams to disk; the original PDF is never accumulated in a byte array.
            Files.copy(input, pdf, StandardCopyOption.REPLACE_EXISTING);
            log.info("开始 PP PDF 解析，fileMd5: {}, generation: {}", fileMd5, generation);
            DocumentParseResult result = parser.apply(pdf); // Client already decodes JSONL and maps it.
            ParsedDocumentArtifacts artifacts = assembler.assemble(result);
            List<TextChunk> chunks = chunker.chunk(artifacts);
            boolean committed = persistence.persist(fileMd5, generation, artifacts, chunks, permissions);
            log.info("PP 解析产物提交结束，fileMd5: {}, generation: {}, committed: {}, chunks: {}, figures: {}",
                    fileMd5, generation, committed, chunks.size(), artifacts.figures().size());
            return committed;
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(pdf);
            } catch (IOException cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }
}
