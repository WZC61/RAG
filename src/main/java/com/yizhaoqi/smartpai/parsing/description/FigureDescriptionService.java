package com.yizhaoqi.smartpai.parsing.description;

import com.yizhaoqi.smartpai.client.FigureDescriptionClient;
import com.yizhaoqi.smartpai.client.FigureDescriptionProperties;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Resumable PARSED-stage artifact preparation, called before content Text/Figure indexing. */
@Service
public class FigureDescriptionService {
    private static final Logger log = LoggerFactory.getLogger(FigureDescriptionService.class);
    private final FileContentRepository contents;
    private final DocumentFigureRepository figures;
    private final FigureImageReader images;
    private final FigureDescriptionClient client;
    private final FigureDescriptionPersistenceService persistence;
    private final FigureDescriptionProperties properties;

    public FigureDescriptionService(FileContentRepository contents, DocumentFigureRepository figures,
                                    FigureImageReader images, FigureDescriptionClient client,
                                    FigureDescriptionPersistenceService persistence,
                                    FigureDescriptionProperties properties) {
        this.contents = contents;
        this.figures = figures;
        this.images = images;
        this.client = client;
        this.persistence = persistence;
        this.properties = properties;
    }

    /** True means descriptions are ready; false means the requested PARSED generation is no longer current. */
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.NOT_SUPPORTED)
    public boolean describe(String fileMd5, long generation) throws IOException {
        validateIdentity(fileMd5, generation);
        if (!isCurrentParsed(fileMd5, generation)) return false;
        List<DocumentFigure> rows = figures
                .findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(fileMd5, generation);
        for (DocumentFigure figure : rows) {
            if (!isCurrentParsed(fileMd5, generation)) return false;
            validateFigure(fileMd5, generation, figure);
            if (figure.getDescription() != null && !figure.getDescription().isBlank()) continue;

            long started = System.nanoTime();
            try {
                FigureImageReader.FigureImage image = images.read(figure.getImagePath());
                if (!isCurrentParsed(fileMd5, generation)) return false;
                String description = client.describe(image.bytes(), image.mimeType(),
                        figure.getCaption(), figure.getOcrText(), figure.getNearbyText());
                if (persistence.save(fileMd5, generation, figure.getId(), description).isEmpty()) {
                    logOutcome(fileMd5, generation, figure, started, "stale");
                    return false;
                }
                logOutcome(fileMd5, generation, figure, started, "success");
            } catch (IOException | RuntimeException failure) {
                // Do not log exception messages/causes: remote bodies may echo credentials or image data.
                logOutcome(fileMd5, generation, figure, started, "failure");
                throw failure;
            }
        }
        return isCurrentParsed(fileMd5, generation);
    }

    private boolean isCurrentParsed(String fileMd5, long generation) {
        return contents.findByFileMd5(fileMd5)
                .filter(content -> fileMd5.equals(content.getFileMd5()) && content.getProcessingGeneration() == generation
                        && content.getProcessingStatus() == FileContent.ProcessingStatus.PARSED)
                .isPresent();
    }

    static void validateIdentity(String fileMd5, long generation) {
        if (fileMd5 == null || !fileMd5.matches("[A-Za-z0-9]{1,32}") || generation < 1)
            throw new IllegalArgumentException("Invalid fileMd5 or processingGeneration");
    }

    private static void validateFigure(String fileMd5, long generation, DocumentFigure figure) throws IOException {
        if (figure == null || figure.getId() == null || !fileMd5.equals(figure.getFileMd5())
                || !Objects.equals(figure.getProcessingGeneration(), generation)
                || figure.getPageNumber() == null || figure.getPageNumber() < 1
                || figure.getFigureIndex() == null || figure.getFigureIndex() < 1)
            throw new IOException("Incomplete or mismatched Figure identity");
        String prefix = "figures/" + fileMd5 + "/" + generation + "/page-" + figure.getPageNumber()
                + "-figure-" + figure.getFigureIndex() + ".";
        String path = figure.getImagePath();
        if (path == null || !(path.equals(prefix + "jpg") || path.equals(prefix + "png") || path.equals(prefix + "webp")))
            throw new IOException("Figure must reference its own stable image path");
    }

    private void logOutcome(String fileMd5, long generation, DocumentFigure figure, long started, String outcome) {
        log.info("Figure description fileMd5={} generation={} pageNumber={} figureIndex={} model={} elapsedMs={} outcome={}",
                fileMd5, generation, figure.getPageNumber(), figure.getFigureIndex(), properties.getModel(),
                (System.nanoTime() - started) / 1_000_000, outcome);
    }
}
