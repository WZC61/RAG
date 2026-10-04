package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunker;
import com.yizhaoqi.smartpai.parsing.chunk.TextChunkFragment;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Legacy parser regression tests with an in-memory repository substitute; no Spring application. */
class ParseServiceTest {
    private ParseService parseService;
    private DocumentVectorRepository repository;
    private UsageQuotaService usageQuota;
    private TextChunker textChunker;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        repository = mock(DocumentVectorRepository.class);
        usageQuota = mock(UsageQuotaService.class);
        textChunker = new TextChunker(20, 4, 1);
        ReflectionTestUtils.setField(parseService, "documentVectorRepository", repository);
        ReflectionTestUtils.setField(parseService, "usageQuotaService", usageQuota);
        ReflectionTestUtils.setField(parseService, "textChunker", textChunker);
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 1048576);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 1.0);
    }

    @Test
    void tikaStillSavesTextAnchorsMetadataAndGlobalChunkIds() throws Exception {
        String text = "First paragraph.\n\nSecond paragraph.";
        List<TextChunkFragment> expected = textChunker.chunk(text);
        parseService.parseAndSave("abc", stream(text), "user-a", "org-a", true);
        List<DocumentVector> saved = savedVectors(expected.size());
        for (int i = 0; i < saved.size(); i++) {
            DocumentVector vector = saved.get(i);
            assertEquals(i + 1, vector.getChunkId());
            assertEquals(expected.get(i).text(), vector.getTextContent());
            assertEquals(expected.get(i).anchorText(), vector.getAnchorText());
            assertNull(vector.getPageNumber());
            assertEquals("abc", vector.getFileMd5());
            assertEquals("user-a", vector.getUserId());
            assertEquals("org-a", vector.getOrgTag());
            assertTrue(vector.isPublic());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tikaTokenEstimationUsesTheSameExtractedChunker() throws Exception {
        String text = "First paragraph.\n\nSecond paragraph.";
        List<String> expected = textChunker.chunk(text).stream().map(TextChunkFragment::text).toList();
        when(usageQuota.estimateEmbeddingTokens(anyList())).thenReturn(42);
        ParseService.EmbeddingEstimate estimate = parseService.estimateEmbeddingUsage(stream(text));
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(usageQuota).estimateEmbeddingTokens(captor.capture());
        assertEquals(expected, captor.getValue());
        assertEquals(expected.size(), estimate.estimatedChunkCount());
        assertEquals(42, estimate.estimatedTokens());
        verifyNoInteractions(repository);
    }

    @Test
    void pageBatchesKeepLegacyFileLevelIdsAndPrecomputedAnchors() throws Exception {
        List<TextChunkFragment> first = textChunker.chunk(1, "第一句。第二句。第三句。第四句。第五句。第六句。");
        List<TextChunkFragment> second = textChunker.chunk(2, "下一页正文");
        Method save = ParseService.class.getDeclaredMethod("saveChildChunks", String.class, List.class,
                String.class, String.class, boolean.class, int.class, Integer.class);
        save.setAccessible(true);
        int count = (int) save.invoke(parseService, "abc", first, "a", "org", false, 0, 1);
        int total = (int) save.invoke(parseService, "abc", second, "a", "org", false, count, 2);
        assertEquals(first.size() + second.size(), total);
        List<DocumentVector> saved = savedVectors(total);
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(i + 1, saved.get(i).getChunkId());
            assertEquals(i < first.size() ? 1 : 2, saved.get(i).getPageNumber());
        }
        assertEquals(second.get(0).anchorText(), saved.get(saved.size() - 1).getAnchorText());
    }

    @Test
    void streamingParentBatchesKeepNumberingWithoutCrossBatchOverlap() throws Exception {
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 8);
        Class<?> handlerClass = Class.forName(ParseService.class.getName() + "$StreamingContentHandler");
        Constructor<?> constructor = handlerClass.getDeclaredConstructor(ParseService.class,
                String.class, String.class, String.class, boolean.class);
        constructor.setAccessible(true);
        BodyContentHandler handler = (BodyContentHandler) constructor.newInstance(parseService, "abc", "a", "org", false);
        String first = "第一句。第二句。第三句。第四句。第五句。第六句。";
        String second = "第七句。第八句。第九句。第十句。";
        handler.characters(first.toCharArray(), 0, first.length());
        handler.characters(second.toCharArray(), 0, second.length());
        handler.endDocument();
        List<String> expected = java.util.stream.Stream.concat(textChunker.chunk(first).stream(),
                        textChunker.chunk(second).stream()).map(TextChunkFragment::text).toList();
        List<DocumentVector> saved = savedVectors(expected.size());
        assertEquals(expected, saved.stream().map(DocumentVector::getTextContent).toList());
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(i + 1, saved.get(i).getChunkId());
            assertNull(saved.get(i).getPageNumber());
        }
    }

    @Test
    void saveUsesFragmentAnchorInsteadOfRecomputingAtRepositoryBoundary() throws Exception {
        Method save = ParseService.class.getDeclaredMethod("saveChildChunks", String.class, List.class,
                String.class, String.class, boolean.class, int.class, Integer.class);
        save.setAccessible(true);
        save.invoke(parseService, "abc", List.of(new TextChunkFragment(1, "正文", "已有锚点")),
                "a", "org", false, 0, 1);
        assertEquals("已有锚点", savedVectors(1).get(0).getAnchorText());
    }

    private List<DocumentVector> savedVectors(int count) {
        ArgumentCaptor<DocumentVector> captor = ArgumentCaptor.forClass(DocumentVector.class);
        verify(repository, times(count)).save(captor.capture());
        return captor.getAllValues();
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
