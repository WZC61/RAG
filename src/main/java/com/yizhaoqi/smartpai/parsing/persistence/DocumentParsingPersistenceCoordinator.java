package com.yizhaoqi.smartpai.parsing.persistence;

import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Prepares every image outside MySQL, then calls a separate transactional service proxy. */
@Service
public class DocumentParsingPersistenceCoordinator {
    private final FileContentProcessingService checkpoints;
    private final FigureImageStorageService images;
    private final ParsedArtifactPersistenceService persistence;

    public DocumentParsingPersistenceCoordinator(FileContentProcessingService checkpoints,
            FigureImageStorageService images, ParsedArtifactPersistenceService persistence) {
        this.checkpoints = checkpoints;
        this.images = images;
        this.persistence = persistence;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.NOT_SUPPORTED)
    public boolean persist(String fileMd5, long generation, ParsedDocumentArtifacts artifacts,
                           List<TextChunk> chunks, LegacyPermissionContext permissions) throws IOException {
        ArtifactValidation.identity(fileMd5, generation);
        // Advisory read only. The locked check in the commit transaction is authoritative.
        if (checkpoints.checkpoint(fileMd5, generation) != FileContent.ProcessingStatus.MERGED) return false;
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(permissions, "permissions");
        ArtifactValidation.chunks(chunks);
        List<TextChunk> snapshot = List.copyOf(chunks);
        if (snapshot.isEmpty() && (artifacts.figures().isEmpty() || artifacts.pages().stream()
                .anyMatch(page -> page.text() != null && !page.text().isBlank())))
            throw new IllegalArgumentException("No generated text chunks or usable figures");
        Set<String> identities = new HashSet<>();
        for (ParsedFigureContent figure : artifacts.figures()) {
            ArtifactValidation.figureIdentity(figure.pageNumber(), figure.figureIndex());
            if (!identities.add(figure.pageNumber() + ":" + figure.figureIndex()))
                throw new IllegalArgumentException("Duplicate figure identity in parsed artifacts");
        }
        List<StoredFigureArtifact> prepared = new ArrayList<>(artifacts.figures().size());
        for (ParsedFigureContent figure : artifacts.figures()) {
            String path = images.store(fileMd5, generation, figure);
            prepared.add(StoredFigureArtifact.from(figure, path));
        }
        // Uploads may leave orphan objects if this transaction fails. Stable generation paths
        // bound retries to the same keys; no distributed transaction or deletion is attempted.
        return persistence.persist(fileMd5, generation, snapshot, List.copyOf(prepared), permissions);
    }
}
