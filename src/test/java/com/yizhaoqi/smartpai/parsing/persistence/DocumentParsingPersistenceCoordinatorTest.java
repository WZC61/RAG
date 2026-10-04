package com.yizhaoqi.smartpai.parsing.persistence;

import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.parsing.model.ParsedDocumentArtifacts;
import com.yizhaoqi.smartpai.parsing.model.ParsedFigureContent;
import com.yizhaoqi.smartpai.parsing.model.ParsedPageContent;
import com.yizhaoqi.smartpai.service.FileContentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentParsingPersistenceCoordinatorTest {
    private FileContentProcessingService checkpoints;
    private FigureImageStorageService images;
    private ParsedArtifactPersistenceService database;
    private DocumentParsingPersistenceCoordinator coordinator;
    private final LegacyPermissionContext permissions = new LegacyPermissionContext("owner-a", "TEAM-A", true);
    private final List<TextChunk> text = List.of(new TextChunk(1, 1, "正文", "正文"));

    @BeforeEach
    void setUp() throws Exception {
        checkpoints = mock(FileContentProcessingService.class);
        images = mock(FigureImageStorageService.class);
        database = mock(ParsedArtifactPersistenceService.class);
        coordinator = new DocumentParsingPersistenceCoordinator(checkpoints, images, database);
        when(checkpoints.checkpoint("abc123", 1)).thenReturn(FileContent.ProcessingStatus.MERGED);
        when(images.store(eq("abc123"), eq(1L), any())).thenAnswer(invocation -> {
            ParsedFigureContent figure = invocation.getArgument(2);
            return "figures/abc123/1/page-" + figure.pageNumber() + "-figure-" + figure.figureIndex() + ".png";
        });
        when(database.persist(anyString(), anyLong(), anyList(), anyList(), any())).thenReturn(true);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void everyFigureFinishesBeforeDatabaseReceivesStableMetadata() throws Exception {
        ParsedFigureContent first = figure(1, "https://images.invalid/first");
        ParsedFigureContent second = figure(2, "https://images.invalid/second");
        assertTrue(coordinator.persist("abc123", 1, document(first, second), text, permissions));
        ArgumentCaptor<List<StoredFigureArtifact>> capture = ArgumentCaptor.forClass((Class) List.class);
        InOrder order = inOrder(images, database);
        order.verify(images).store("abc123", 1, first);
        order.verify(images).store("abc123", 1, second);
        order.verify(database).persist(eq("abc123"), eq(1L), eq(text), capture.capture(), eq(permissions));
        assertEquals(2, capture.getValue().size());
        assertEquals("figures/abc123/1/page-1-figure-2.png", capture.getValue().get(1).imagePath());
        assertEquals("caption", capture.getValue().get(0).caption());
        assertEquals("ocr", capture.getValue().get(0).ocrText());
        assertEquals("nearby", capture.getValue().get(0).nearbyText());
        assertFalse(capture.getValue().toString().contains("https://"));
    }

    @Test
    void secondFigureFailureDoesNotCallDatabase() throws Exception {
        ParsedFigureContent second = figure(2, "https://images.invalid/second");
        doThrow(new IOException("second failed")).when(images).store("abc123", 1, second);
        assertThrows(IOException.class, () -> coordinator.persist("abc123", 1,
                document(figure(1, "https://images.invalid/first"), second), text, permissions));
        verify(images).store(eq("abc123"), eq(1L), argThat(figure -> figure.figureIndex() == 1));
        verifyNoInteractions(database);
    }

    @Test
    void missingFigureUrlFailureDoesNotCallDatabase() throws Exception {
        ParsedFigureContent figure = figure(1, null);
        doThrow(new IOException("sourceImageUrl missing")).when(images).store("abc123", 1, figure);
        assertThrows(IOException.class, () -> coordinator.persist("abc123", 1, document(figure), text, permissions));
        verifyNoInteractions(database);
    }

    @Test
    void noFigureDocumentCanCommit() throws Exception {
        assertTrue(coordinator.persist("abc123", 1, document(), text, permissions));
        verifyNoInteractions(images);
        verify(database).persist("abc123", 1, text, List.of(), permissions);
    }

    @Test
    void figureOnlyDocumentCanCommit() throws Exception {
        ParsedDocumentArtifacts artifacts = new ParsedDocumentArtifacts(List.of(), List.of(figure(1, "https://images.invalid/a")));
        assertTrue(coordinator.persist("abc123", 1, artifacts, List.of(), permissions));
        verify(database).persist(eq("abc123"), eq(1L), eq(List.of()), argThat(figures -> figures.size() == 1), eq(permissions));
    }

    @Test
    void emptyArtifactsNeverCommit() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.persist("abc123", 1,
                new ParsedDocumentArtifacts(List.of(), List.of()), List.of(), permissions));
        verifyNoInteractions(images, database);
    }

    @Test
    void bodyTextWithoutGeneratedChunksNeverCommits() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.persist("abc123", 1,
                document(figure(1, "https://images.invalid/a")), List.of(), permissions));
        verifyNoInteractions(images, database);
    }

    @ParameterizedTest
    @EnumSource(value = FileContent.ProcessingStatus.class, names = {"PARSED", "INDEXED", "FAILED"})
    void finishedOrTerminalCheckpointsSkipExternalPreparation(FileContent.ProcessingStatus status) throws Exception {
        when(checkpoints.checkpoint("abc123", 1)).thenReturn(status);
        assertFalse(coordinator.persist("abc123", 1, document(figure(1, "https://images.invalid/a")), text, permissions));
        verifyNoInteractions(images, database);
    }

    @Test
    void staleGenerationSkipsExternalPreparation() throws Exception {
        when(checkpoints.checkpoint("abc123", 1)).thenReturn(null);
        assertFalse(coordinator.persist("abc123", 1, document(), text, permissions));
        verifyNoInteractions(images, database);
    }

    @Test
    void commitFailureIsPropagatedAfterImagePreparation() throws Exception {
        doThrow(new IllegalStateException("DB failed")).when(database).persist(anyString(), anyLong(), anyList(), anyList(), any());
        assertThrows(IllegalStateException.class, () -> coordinator.persist("abc123", 1,
                document(figure(1, "https://images.invalid/a")), text, permissions));
        verify(images).store(eq("abc123"), eq(1L), any());
    }

    @Test
    void lockedGenerationCheckCanSkipAfterSuccessfulPreparation() throws Exception {
        when(database.persist(anyString(), anyLong(), anyList(), anyList(), any())).thenReturn(false);
        assertFalse(coordinator.persist("abc123", 1, document(figure(1, "https://images.invalid/a")), text, permissions));
        verify(images).store(eq("abc123"), eq(1L), any());
    }

    @Test
    void duplicateFigureIdentityIsRejectedBeforeImagePreparation() {
        ParsedFigureContent figure = figure(1, "https://images.invalid/a");
        assertThrows(IllegalArgumentException.class, () -> coordinator.persist("abc123", 1, document(figure, figure), text, permissions));
        verifyNoInteractions(images, database);
    }

    private static ParsedDocumentArtifacts document(ParsedFigureContent... figures) {
        return new ParsedDocumentArtifacts(List.of(new ParsedPageContent(1, "正文")), List.of(figures));
    }

    private static ParsedFigureContent figure(int index, String url) {
        return new ParsedFigureContent(1, index, "Figure " + index, List.of(0, 1, 2, 3), "caption", "ocr", "nearby", "key", url);
    }
}
