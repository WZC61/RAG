package com.yizhaoqi.smartpai.parsing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Short MySQL-only commit of a complete, externally prepared artifact set. */
@Service
public class ParsedArtifactPersistenceService {
    private final FileContentRepository contents;
    private final DocumentVectorRepository vectors;
    private final DocumentFigureRepository figures;
    private final ObjectMapper mapper;

    public ParsedArtifactPersistenceService(FileContentRepository contents, DocumentVectorRepository vectors,
                                            DocumentFigureRepository figures, ObjectMapper mapper) {
        this.contents = contents;
        this.vectors = vectors;
        this.figures = figures;
        this.mapper = mapper;
    }

    /** False means a stale/finished/terminal task: no rows were replaced. */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public boolean persist(String fileMd5, long generation, List<TextChunk> chunks,
                           List<StoredFigureArtifact> preparedFigures, LegacyPermissionContext permissions)
            throws JsonProcessingException {
        ArtifactValidation.identity(fileMd5, generation);
        FileContent content = contents.findForUpdate(fileMd5).orElseThrow();
        // Same checkpoints as FileContentProcessingService: FAILED is terminal, INDEXED is final;
        // PARSED already owns durable artifacts and must not be replaced by a delayed parse.
        if (content.getProcessingGeneration() != generation
                || content.getProcessingStatus() != FileContent.ProcessingStatus.MERGED) return false;

        ArtifactValidation.chunks(chunks);
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(preparedFigures, "preparedFigures");
        if (chunks.isEmpty() && preparedFigures.isEmpty())
            throw new IllegalArgumentException("No text or figure artifacts to persist");

        List<DocumentVector> textRows = chunks.stream().map(chunk -> {
            DocumentVector row = new DocumentVector();
            row.setFileMd5(fileMd5);
            row.setChunkId(chunk.chunkIndex());
            row.setTextContent(chunk.text());
            row.setPageNumber(chunk.pageNumber());
            row.setAnchorText(chunk.anchorText());
            row.setUserId(permissions.userId());
            row.setOrgTag(permissions.orgTag());
            row.setPublic(permissions.isPublic());
            // Legacy ParseService does not set modelVersion on initial parse; preserve null.
            return row;
        }).toList();
        List<DocumentFigure> figureRows = new ArrayList<>(preparedFigures.size());
        for (StoredFigureArtifact figure : preparedFigures) {
            Objects.requireNonNull(figure, "prepared figure");
            String prefix = ArtifactValidation.figurePathPrefix(fileMd5, generation, figure.pageNumber(), figure.figureIndex());
            if (figure.imagePath() == null || !figure.imagePath().startsWith(prefix))
                throw new IllegalArgumentException("Figure must reference its prepared stable MinIO path");
            DocumentFigure row = new DocumentFigure();
            row.setFileMd5(fileMd5);
            row.setProcessingGeneration(generation);
            row.setPageNumber(figure.pageNumber());
            row.setFigureIndex(figure.figureIndex());
            row.setFigureLabel(figure.figureLabel());
            row.setImagePath(figure.imagePath());
            row.setBbox(mapper.writeValueAsString(figure.bbox()));
            row.setCaption(figure.caption());
            row.setOcrText(figure.ocrText());
            row.setNearbyText(figure.nearbyText());
            // Description is a later-stage artifact and is not required for PARSED.
            figureRows.add(row);
        }

        vectors.deleteByFileMd5(fileMd5);
        figures.deleteByFileMd5(fileMd5);
        vectors.saveAll(textRows);
        figures.saveAll(figureRows);
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        content.setProcessingError(null);
        // Flush all pending inserts and the checkpoint; any failure rolls back the full replace.
        contents.saveAndFlush(content);
        return true;
    }
}
