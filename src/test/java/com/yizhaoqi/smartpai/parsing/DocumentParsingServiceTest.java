package com.yizhaoqi.smartpai.parsing;

import com.yizhaoqi.smartpai.parsing.chunk.ParsedDocumentChunker;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunk;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunker;
import com.yizhaoqi.smartpai.parsing.model.*;
import com.yizhaoqi.smartpai.parsing.persistence.DocumentParsingPersistenceCoordinator;
import com.yizhaoqi.smartpai.parsing.persistence.LegacyPermissionContext;
import com.yizhaoqi.smartpai.parsing.pp.PpStructureApiClient;
import com.yizhaoqi.smartpai.parsing.pp.PpStructureApiConfiguration;
import com.yizhaoqi.smartpai.parsing.pp.PpStructureApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentParsingServiceTest {
    private final LegacyPermissionContext permissions = new LegacyPermissionContext("1", "TEAM_A", true);
    private final ParsedDocumentChunker chunker = new ParsedDocumentChunker(new TextChunker(16, 0, 0));
    private DocumentParsingPersistenceCoordinator persistence;
    private AtomicReference<Path> submitted;

    @BeforeEach
    void setUp() throws Exception {
        submitted = new AtomicReference<>();
        persistence = mock(DocumentParsingPersistenceCoordinator.class);
        when(persistence.persist(anyString(), anyLong(), any(), anyList(), any())).thenReturn(true);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void realAssemblyAndChunkingFeedPersistenceAndTemporaryPdfIsDeleted() throws Exception {
        DocumentParsingService service = service(path -> {
            assertTrue(Files.exists(path));
            assertTrue(path.toString().endsWith(".pdf"));
            try { assertEquals("%PDF-1.7\nfake", Files.readString(path)); }
            catch (IOException e) { throw new RuntimeException(e); }
            return result();
        });
        assertTrue(service.parseAndPersist("md5", 1, input(), permissions));
        ArgumentCaptor<ParsedDocumentArtifacts> artifacts = ArgumentCaptor.forClass(ParsedDocumentArtifacts.class);
        ArgumentCaptor<List<TextChunk>> chunks = ArgumentCaptor.forClass((Class) List.class);
        verify(persistence).persist(eq("md5"), eq(1L), artifacts.capture(), chunks.capture(), eq(permissions));
        assertEquals(List.of("第一页正文", "第二页正文"), artifacts.getValue().pages().stream().map(ParsedPageContent::text).toList());
        assertEquals(1, artifacts.getValue().figures().size());
        assertEquals("https://images.invalid/figure", artifacts.getValue().figures().get(0).sourceImageUrl());
        assertEquals(List.of(1, 2), chunks.getValue().stream().map(TextChunk::chunkIndex).toList());
        assertEquals(List.of(1, 2), chunks.getValue().stream().map(TextChunk::pageNumber).toList());
        assertEquals("第一页正文", chunks.getValue().get(0).anchorText());
        assertFalse(Files.exists(submitted.get()));
    }

    @Test
    void ppFailurePropagatesWithMetadataAndStillDeletesTemporaryPdf() {
        PpStructureApiException error = new PpStructureApiException(PpStructureApiException.Stage.POLL, 503, null, "trace", "job", "poll failed");
        DocumentParsingService service = service(path -> { throw error; });
        assertSame(error, assertThrows(PpStructureApiException.class, () -> service.parseAndPersist("md5", 1, input(), permissions)));
        assertFalse(Files.exists(submitted.get()));
        verifyNoInteractions(persistence);
    }

    @Test
    void invalidAssemblyNeverPersistsAndDeletesTemporaryPdf() {
        DocumentParsingService service = service(path -> new DocumentParseResult(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.parseAndPersist("md5", 1, input(), permissions));
        assertFalse(Files.exists(submitted.get()));
        verifyNoInteractions(persistence);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Figure download failed", "MinIO failed", "DB persistence failed"})
    void preparationOrCommitFailurePropagatesAndDeletesTemporaryPdf(String message) throws Exception {
        doThrow(new IOException(message)).when(persistence).persist(anyString(), anyLong(), any(), anyList(), any());
        DocumentParsingService service = service(path -> result());
        assertEquals(message, assertThrows(IOException.class, () -> service.parseAndPersist("md5", 1, input(), permissions)).getMessage());
        assertFalse(Files.exists(submitted.get()));
    }

    @Test
    void stalePersistenceResultIsReturnedWithoutTurningItIntoFailure() throws Exception {
        when(persistence.persist(anyString(), anyLong(), any(), anyList(), any())).thenReturn(false);
        assertFalse(service(path -> result()).parseAndPersist("md5", 1, input(), permissions));
        assertFalse(Files.exists(submitted.get()));
    }

    @Test
    void disabledClientGivesExplicitConfigurationErrorWithoutLegacyFallback() {
        DocumentParsingService service = new DocumentParsingService(
                new DefaultListableBeanFactory().getBeanProvider(PpStructureApiClient.class), chunker, persistence);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.parseAndPersist("md5", 1, input(), permissions));
        assertTrue(error.getMessage().contains("PP-StructureV3 disabled"));
        assertTrue(error.getMessage().contains("PADDLEOCR_ACCESS_TOKEN"));
        verifyNoInteractions(persistence);
    }

    @Test
    void disabledConfigurationStillCreatesParsingServiceForNonPdfApplications() {
        new ApplicationContextRunner().withUserConfiguration(PpStructureApiConfiguration.class, DocumentParsingService.class)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(ParsedDocumentChunker.class, () -> chunker)
                .withBean(DocumentParsingPersistenceCoordinator.class, () -> persistence)
                .withPropertyValues("paddle.pp-structure.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(PpStructureApiClient.class).size());
                    IllegalStateException error = assertThrows(IllegalStateException.class,
                            () -> context.getBean(DocumentParsingService.class).parseAndPersist("md5", 1, input(), permissions));
                    assertTrue(error.getMessage().contains("disabled"));
                });
    }

    @Test
    void originalPdfIsStreamedToDiskWithoutReadAllBytesAndInputRemainsCallerOwned() throws Exception {
        long size = 5L * 1024 * 1024;
        GeneratedPdfStream input = new GeneratedPdfStream(size);
        DocumentParsingService service = service(path -> {
            try { assertEquals(size, Files.size(path)); }
            catch (IOException e) { throw new RuntimeException(e); }
            return result();
        });
        assertTrue(service.parseAndPersist("md5", 1, input, permissions));
        assertFalse(input.closed);
        assertEquals(size, input.position);
        assertFalse(Files.exists(submitted.get()));
        input.close();
    }

    @Test
    void partialDownloadFailureDeletesTemporaryFileAndNeverCallsPp() throws Exception {
        Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> before = tempPdfs(tempDirectory);
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("merged stream interrupted"); }
        };
        DocumentParsingService service = service(path -> fail("PP must not be called after a copy failure"));
        IOException error = assertThrows(IOException.class, () -> service.parseAndPersist("md5", 1, broken, permissions));
        assertEquals("merged stream interrupted", error.getMessage());
        assertNull(submitted.get());
        assertEquals(before, tempPdfs(tempDirectory));
        verifyNoInteractions(persistence);
    }

    private DocumentParsingService service(Function<Path, DocumentParseResult> parser) {
        return new DocumentParsingService(() -> path -> {
            submitted.set(path);
            return parser.apply(path);
        }, chunker, persistence);
    }

    private static DocumentParseResult result() {
        return new DocumentParseResult(List.of(new PageParseResult(1,
                List.of(new ParseBlock("text", "第一页正文", List.of(), 1, 1)),
                List.of(new FigureParseResult(1, 1, "Figure 1", List.of(), "caption", "OCR", null,
                        "key", "https://images.invalid/figure"))),
                new PageParseResult(2, List.of(new ParseBlock("text", "第二页正文", List.of(), 2, 2)), List.of())));
    }

    private static ByteArrayInputStream input() {
        return new ByteArrayInputStream("%PDF-1.7\nfake".getBytes(StandardCharsets.US_ASCII));
    }

    private static List<Path> tempPdfs(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("paismart-pp-")
                    && path.toString().endsWith(".pdf")).sorted().toList();
        }
    }

    private static final class GeneratedPdfStream extends InputStream {
        private final long size;
        private long position;
        private boolean closed;
        private static final byte[] HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

        GeneratedPdfStream(long size) { this.size = size; }

        @Override public int read() {
            if (position == size) return -1;
            return position < HEADER.length ? HEADER[(int) position++] : nextByte();
        }
        private int nextByte() { position++; return 'x'; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            if (position == size) return -1;
            int count = (int) Math.min(length, size - position);
            for (int i = 0; i < count; i++) bytes[offset + i] = (byte) read();
            return count;
        }
        @Override public byte[] readAllBytes() { throw new AssertionError("Entire PDF must not be buffered"); }
        @Override public void close() { closed = true; }
    }
}
