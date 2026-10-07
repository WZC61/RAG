package com.yizhaoqi.smartpai.parsing.description;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yizhaoqi.smartpai.client.FigureDescriptionClient;
import com.yizhaoqi.smartpai.client.FigureDescriptionProperties;
import com.yizhaoqi.smartpai.model.DocumentFigure;
import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FigureDescriptionServiceTest {
    private static final String MD5 = "abc123";
    private final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private FileContentRepository contents;
    private DocumentFigureRepository figures;
    private FigureImageReader images;
    private FigureDescriptionClient client;
    private FigureDescriptionPersistenceService persistence;
    private FigureDescriptionService service;
    private FileContent content;

    @BeforeEach
    void setUp() throws IOException {
        contents = mock(FileContentRepository.class);
        figures = mock(DocumentFigureRepository.class);
        images = mock(FigureImageReader.class);
        client = mock(FigureDescriptionClient.class);
        persistence = mock(FigureDescriptionPersistenceService.class);
        FigureDescriptionProperties properties = new FigureDescriptionProperties();
        service = new FigureDescriptionService(contents, figures, images, client, persistence, properties);
        content = new FileContent();
        content.setFileMd5(MD5);
        content.setProcessingGeneration(1);
        content.setProcessingStatus(FileContent.ProcessingStatus.PARSED);
        when(contents.findByFileMd5(MD5)).thenAnswer(invocation -> Optional.of(content));
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of());
        when(images.read(anyString())).thenReturn(new FigureImageReader.FigureImage(png, "image/png"));
        when(client.describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("模型描述");
        when(persistence.save(eq(MD5), eq(1L), anyLong(), eq("模型描述"))).thenReturn(Optional.of("模型描述"));
    }

    @Test
    void emptyDescriptionReadsImageDescribesAndPersists() throws Exception {
        DocumentFigure figure = figure(1, 1, 7);
        figure.setCaption("图注");
        figure.setOcrText("图中文字");
        figure.setNearbyText("附近正文");
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure));
        assertTrue(service.describe(MD5, 1));
        verify(images).read(figure.getImagePath());
        verify(client).describe(png, "image/png", "图注", "图中文字", "附近正文");
        verify(persistence).save(MD5, 1, 7, "模型描述");
    }

    @Test
    void nonblankDescriptionIsReusedWithoutReadingImageOrCallingModel() throws Exception {
        DocumentFigure figure = figure(1, 1, 7);
        figure.setDescription(" 已有描述 ");
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure));
        assertTrue(service.describe(MD5, 1));
        verifyNoInteractions(images, client, persistence);
    }

    @Test
    void whitespaceDescriptionStillRequiresGeneration() throws Exception {
        DocumentFigure figure = figure(1, 1, 7);
        figure.setDescription(" \t\n ");
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure));
        assertTrue(service.describe(MD5, 1));
        verify(persistence).save(MD5, 1, 7, "模型描述");
    }

    @Test
    void repositoryQueryAndProcessingUseStablePageAndFigureOrder() throws Exception {
        DocumentFigure first = figure(1, 1, 7);
        DocumentFigure second = figure(1, 2, 8);
        DocumentFigure third = figure(2, 1, 9);
        first.setCaption("first");
        second.setCaption("second");
        third.setCaption("third");
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(first, second, third));
        assertTrue(service.describe(MD5, 1));
        verify(figures).findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L);
        InOrder order = inOrder(images, client, persistence);
        for (DocumentFigure figure : List.of(first, second, third)) {
            order.verify(images).read(figure.getImagePath());
            order.verify(client).describe(png, "image/png", figure.getCaption(), null, null);
            order.verify(persistence).save(MD5, 1, figure.getId(), "模型描述");
        }
    }

    @Test
    void nullCaptionOcrAndNearbyContextAreAllowed() throws Exception {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure(1, 1, 7)));
        assertTrue(service.describe(MD5, 1));
        verify(client).describe(png, "image/png", null, null, null);
    }

    @Test
    void missingMinioImageFailsWithoutModelOrDescriptionWrite() throws Exception {
        DocumentFigure figure = figure(1, 1, 7);
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure));
        when(images.read(figure.getImagePath())).thenThrow(new IOException("object not found"));
        assertThrows(IOException.class, () -> service.describe(MD5, 1));
        verifyNoInteractions(client, persistence);
    }

    @Test
    void modelFailureNeverSavesDescription() throws Exception {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure(1, 1, 7)));
        when(client.describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenThrow(new IOException("model unavailable"));
        assertThrows(IOException.class, () -> service.describe(MD5, 1));
        verifyNoInteractions(persistence);
    }

    @Test
    void staleGenerationDoesNotReadImagesOrCallModel() throws Exception {
        content.setProcessingGeneration(2);
        assertFalse(service.describe(MD5, 1));
        verifyNoInteractions(images, client, persistence);
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"MERGED", "INDEXED", "FAILED"})
    void nonParsedStateIsNotProcessed(FileContent.ProcessingStatus status) throws Exception {
        content.setProcessingStatus(status);
        assertFalse(service.describe(MD5, 1));
        verifyNoInteractions(images, client, persistence);
    }

    @Test
    void missingContentIsNotProcessed() throws Exception {
        when(contents.findByFileMd5(MD5)).thenReturn(Optional.empty());
        assertFalse(service.describe(MD5, 1));
        verifyNoInteractions(images, client, persistence);
    }

    @Test
    void noFiguresIsAlreadyCompleteWithoutModelCalls() throws Exception {
        assertTrue(service.describe(MD5, 1));
        verifyNoInteractions(images, client, persistence);
    }

    @Test
    void staleWriteResultStopsRemainingFigureWork() throws Exception {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure(1, 1, 7), figure(1, 2, 8)));
        when(persistence.save(MD5, 1, 7, "模型描述")).thenReturn(Optional.empty());
        assertFalse(service.describe(MD5, 1));
        verify(images, times(1)).read(anyString());
        verify(client, times(1)).describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class));
        verify(persistence, never()).save(eq(MD5), eq(1L), eq(8L), anyString());
    }

    @Test
    void figureCannotReadImageOutsideItsOwnStableIdentityPath() throws Exception {
        DocumentFigure figure = figure(1, 1, 7);
        figure.setImagePath("figures/another/1/page-1-figure-1.png");
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure));
        assertThrows(IOException.class, () -> service.describe(MD5, 1));
        verifyNoInteractions(images, client, persistence);
    }

    @Test
    void failureLogsOnlyIdentityAndNeverSensitiveExceptionBody() throws Exception {
        when(figures.findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(MD5, 1L))
                .thenReturn(List.of(figure(1, 1, 7)));
        String sensitive = "key-for-test data:image/png;base64,aGVsbG8= https://invalid/?X-Amz-Signature=test";
        when(client.describe(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenThrow(new IOException(sensitive));
        Logger logger = (Logger) LoggerFactory.getLogger(FigureDescriptionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(IOException.class, () -> service.describe(MD5, 1));
            assertFalse(appender.list.isEmpty());
            for (ILoggingEvent event : appender.list) {
                String message = event.getFormattedMessage();
                assertFalse(message.contains("key-for-test"));
                assertFalse(message.contains("base64"));
                assertFalse(message.contains("X-Amz-Signature"));
                assertFalse(message.contains("https://"));
                assertNull(event.getThrowableProxy());
            }
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("fileMd5=abc123")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static DocumentFigure figure(int page, int index, long id) {
        DocumentFigure figure = new DocumentFigure();
        figure.setId(id);
        figure.setFileMd5(MD5);
        figure.setProcessingGeneration(1L);
        figure.setPageNumber(page);
        figure.setFigureIndex(index);
        figure.setImagePath("figures/" + MD5 + "/1/page-" + page + "-figure-" + index + ".png");
        return figure;
    }
}
