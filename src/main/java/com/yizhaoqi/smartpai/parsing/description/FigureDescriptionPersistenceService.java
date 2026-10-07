package com.yizhaoqi.smartpai.parsing.description;

import com.yizhaoqi.smartpai.client.FigureDescriptionProperties;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.Optional;

/** Commits one externally generated description; never holds a database lock during HTTP calls. */
@Service
public class FigureDescriptionPersistenceService {
    private final FileContentRepository contents;
    private final DocumentFigureRepository figures;
    private final FigureDescriptionProperties properties;

    public FigureDescriptionPersistenceService(FileContentRepository contents, DocumentFigureRepository figures,
                                               FigureDescriptionProperties properties) {
        this.contents = contents;
        this.figures = figures;
        this.properties = properties;
    }

    /** Empty means this generation can no longer be updated. An existing description wins a race. */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public Optional<String> save(String fileMd5, long generation, long figureId, String description) {
        FigureDescriptionService.validateIdentity(fileMd5, generation);
        FileContent content = contents.findForUpdate(fileMd5).orElse(null);
        if (content == null || !fileMd5.equals(content.getFileMd5()) || content.getProcessingGeneration() != generation
                || content.getProcessingStatus() != FileContent.ProcessingStatus.PARSED) return Optional.empty();

        // This lock also coordinates with parsed-artifact replacement and state transitions.
        // Re-read the Figure in this fresh transaction instead of merging the caller's detached row.
        DocumentFigure figure = figures.findById(figureId).orElse(null);
        if (figure == null || !fileMd5.equals(figure.getFileMd5())
                || !Objects.equals(figure.getProcessingGeneration(), generation)) return Optional.empty();
        if (figure.getDescription() != null && !figure.getDescription().isBlank())
            return Optional.of(figure.getDescription());

        if (description == null || description.trim().isEmpty())
            throw new IllegalArgumentException("Figure description must not be empty");
        String trimmed = description.trim();
        if (properties.getMaxDescriptionLength() < 1 || trimmed.length() > properties.getMaxDescriptionLength())
            throw new IllegalArgumentException("Figure description exceeds the configured length limit");
        figure.setDescription(trimmed);
        figures.saveAndFlush(figure);
        return Optional.of(trimmed);
    }
}
