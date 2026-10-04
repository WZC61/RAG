package com.yizhaoqi.smartpai.parsing.chunk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.hankcs.hanlp.seg.common.Term;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Original ParseService algorithm regressions, migrated without changing assertions. */
class TextChunkerTest {
    private TextChunker textChunker;

    @BeforeEach
    void setUp() {
        textChunker = new TextChunker(1000, 0, 1);
    }

    @Test
    void testSplitLongSentence_BasicFunctionality() throws Exception {
        // 测试基本功能
        String sentence = "这是一个测试句子，用来验证分词效果。";
        int chunkSize = 15;

        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 验证拼接后等于原文
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed);
        
        System.out.println("=== 基本功能测试 ===");
        System.out.println("原文: " + sentence + " (长度: " + sentence.length() + ")");
        System.out.println("分块数量: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("分块 " + i + ": " + result.get(i) + " (长度: " + result.get(i).length() + ")");
        }
    }

    @Test
    void testSplitLongSentence_EdgeCases() throws Exception {
        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        // 测试空字符串
        @SuppressWarnings("unchecked")
        List<String> emptyResult = (List<String>) method.invoke(textChunker, "", 100);
        assertTrue(emptyResult.isEmpty() || (emptyResult.size() == 1 && emptyResult.get(0).isEmpty()));

        // 测试单个字符
        @SuppressWarnings("unchecked")
        List<String> singleCharResult = (List<String>) method.invoke(textChunker, "测", 10);
        assertEquals(1, singleCharResult.size());
        assertEquals("测", singleCharResult.get(0));

        // 测试很长的文本
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("这是第").append(i).append("段文本。");
        }
        
        @SuppressWarnings("unchecked")
        List<String> longResult = (List<String>) method.invoke(textChunker, longText.toString(), 30);
        assertTrue(longResult.size() > 1);
        
        // 验证拼接
        String reconstructed = String.join("", longResult);
        assertEquals(longText.toString(), reconstructed);

        System.out.println("=== 边界情况测试 ===");
        System.out.println("长文本分块数量: " + longResult.size());
    }

    @Test
    void testSplitLongSentence_ChunkSizeValidation() throws Exception {
        String sentence = "这是用来测试分块大小限制的句子，包含标点符号和数字123。";
        
        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        // 测试不同的分块大小
        int[] chunkSizes = {5, 10, 20, 50};
        
        for (int chunkSize : chunkSizes) {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);
            
            // 验证每个分块（除了最后一个）都不超过限制
            for (int i = 0; i < result.size() - 1; i++) {
                assertTrue(result.get(i).length() <= chunkSize, 
                    "分块大小 " + chunkSize + " 时，分块 " + i + " 长度超限: " + result.get(i).length());
            }
            
            // 验证拼接结果
            String reconstructed = String.join("", result);
            assertEquals(sentence, reconstructed, "分块大小 " + chunkSize + " 时拼接结果不匹配");
            
            System.out.println("分块大小 " + chunkSize + " -> 分块数量: " + result.size());
        }
    }

    @Test
    void testSplitLongSentence_Performance() throws Exception {
        // 性能测试
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeText.append("这是一个用于性能测试的长句子，包含各种中文字符和标点符号。");
        }
        
        String sentence = largeText.toString();
        int chunkSize = 100;
        
        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        long startTime = System.currentTimeMillis();
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        assertNotNull(result);
        assertTrue(result.size() > 1);
        
        // 验证拼接结果
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed);

        System.out.println("=== 性能测试 ===");
        System.out.println("原文长度: " + sentence.length());
        System.out.println("分块数量: " + result.size());
        System.out.println("处理时间: " + duration + "ms");
        
        // 性能断言：处理时间应该在合理范围内
        assertTrue(duration < 5000, "处理时间过长: " + duration + "ms");
    }

    @Test
    void testSplitTextIntoChunksWithSemantics_AddsSentenceOverlap() throws Exception {
        textChunker = new TextChunker(20, 10, 1);

        String text = "第一句内容较长。第二句内容较长。第三句内容较长。第四句内容较长。";

        List<String> result = splitTextIntoChunksWithSemantics(text, 20);

        assertEquals(2, result.size());
        assertEquals("第一句内容较长。第二句内容较长。", result.get(0));
        assertTrue(result.get(1).startsWith("第二句内容较长。\n\n第三句内容较长。"));
    }

    @Test
    void testSplitTextIntoChunksWithSemantics_MergesShortChunks() throws Exception {
        textChunker = new TextChunker(20, 0, 10);

        String text = "标题\n\n第一句内容较长。第二句内容较长。第三句内容较长。";

        List<String> result = splitTextIntoChunksWithSemantics(text, 20);

        assertFalse(result.contains("标题"));
        assertTrue(result.get(0).startsWith("标题\n\n第一句内容较长。"));
        assertTrue(result.stream().allMatch(chunk -> chunk != null && !chunk.isBlank()));
    }

    @Test
    void testSplitTextIntoChunksWithSemantics_EmptyTextReturnsNoChunks() throws Exception {
        assertTrue(splitTextIntoChunksWithSemantics("", 16).isEmpty());
        assertTrue(splitTextIntoChunksWithSemantics("   \n\n  ", 16).isEmpty());
    }

    @Test
    void testSplitLongSentence_NormalChineseText() throws Exception {
        // 准备测试数据 - 正常中文文本
        String sentence = "这是一个测试句子，用来验证HanLP分词功能是否正常工作。我们需要确保它能够正确地进行语义切割，而不是简单的字符分割。";
        int chunkSize = 30;

        // 使用反射调用私有方法
        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        // 验证结果
        assertNotNull(result, "分割结果不应为空");
        assertFalse(result.isEmpty(), "分割结果不应为空列表");
        
        // 验证每个分块的长度都不超过限制（除了最后一个可能较短）
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).length() <= chunkSize, 
                "分块 " + i + " 的长度超过了限制: " + result.get(i).length());
        }
        
        // 验证所有分块拼接后等于原文
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "分割后重新拼接应该等于原文");
        
        // 打印结果用于调试
        System.out.println("原文长度: " + sentence.length());
        System.out.println("分块数量: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("分块 " + i + " (长度:" + result.get(i).length() + "): " + result.get(i));
        }
    }

    @Test
    void testSplitLongSentence_ShortText() throws Exception {
        // 准备测试数据 - 短文本
        String sentence = "短文本测试";
        int chunkSize = 100;

        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        // 验证结果 - 短文本应该只有一个分块
        assertEquals(1, result.size(), "短文本应该只有一个分块");
        assertEquals(sentence, result.get(0), "短文本分块内容应该等于原文");
    }

    @Test
    void testSplitLongSentence_EmptyText() throws Exception {
        // 准备测试数据 - 空文本
        String sentence = "";
        int chunkSize = 100;

        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        // 验证结果 - 空文本应该返回空列表或包含一个空字符串
        assertTrue(result.isEmpty() || (result.size() == 1 && result.get(0).isEmpty()), 
            "空文本应该返回空列表或包含一个空字符串");
    }

    @Test
    void testSplitLongSentence_MixedLanguage() throws Exception {
        // 准备测试数据 - 中英文混合
        String sentence = "这是一个Chinese and English混合的text文本，用来测试mixed language处理能力。";
        int chunkSize = 25;

        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        // 验证结果
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 验证拼接后等于原文
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "混合语言文本分割后重新拼接应该等于原文");
        
        System.out.println("混合语言测试 - 原文长度: " + sentence.length());
        System.out.println("分块数量: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("分块 " + i + ": " + result.get(i));
        }
    }

    @Test
    void testSplitLongSentence_VerySmallChunkSize() throws Exception {
        // 准备测试数据 - 非常小的分块大小
        String sentence = "测试极小分块";
        int chunkSize = 3;

        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        // 验证结果
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 验证拼接后等于原文
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "极小分块测试重新拼接应该等于原文");
    }

    @Test
    void testSplitLongSentence_LongText() throws Exception {
        // 准备测试数据 - 长文本
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText.append("这是一个很长的测试文本，用来验证HanLP分词在处理长文本时的性能和准确性。");
            longText.append("我们希望它能够智能地根据语义进行分割，而不是简单地按照字符数量进行切分。");
        }
        
        String sentence = longText.toString();
        int chunkSize = 50;

        Method method = TextChunker.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(textChunker, sentence, chunkSize);

        // 验证结果
        assertNotNull(result);
        assertTrue(result.size() > 1, "长文本应该被分割成多个块");
        
        // 验证拼接后等于原文
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed, "长文本分割后重新拼接应该等于原文");
        
        System.out.println("长文本测试 - 原文长度: " + sentence.length());
        System.out.println("分块数量: " + result.size());
    }
    @Test
    void shortTextHasPageAndAnchorButNoDocumentIndex() {
        assertEquals(List.of(new TextChunkFragment(3, "普通短文本", "普通短文本")),
                textChunker.chunk(3, "普通短文本"));
    }

    @Test
    void consecutiveBlankLinesSplitParagraphsAndKeepOrder() {
        TextChunker chunker = new TextChunker(10, 0, 0);
        assertEquals(List.of("第一段\n\n第二段", "第三段"),
                texts(chunker.chunk(1, "第一段\n\n\n第二段\n\n第三段")));
    }

    @Test
    void chineseSentenceBoundariesSplitLongParagraph() {
        assertEquals(List.of("第一句。第二句！", "第三句？第四句；"),
                texts(new TextChunker(8, 0, 0).chunk("第一句。第二句！第三句？第四句；")));
    }

    @Test
    void englishSentenceBoundariesRetainLegacyWhitespaceBehavior() {
        assertEquals(List.of("One.Two!", "Three?End;"),
                texts(new TextChunker(10, 0, 0).chunk("One. Two! Three? End;")));
    }

    @Test
    void longSingleSentenceUsesHanlpTokenBoundaries() {
        TextChunker chunker = new TextChunker(3, 0, 0, sentence -> {
            assertEquals("甲乙丙丁戊己", sentence);
            return List.of(new Term("甲乙", null), new Term("丙丁", null), new Term("戊己", null));
        });
        assertEquals(List.of("甲乙", "丙丁", "戊己"), texts(chunker.chunk("甲乙丙丁戊己")));
    }

    @Test
    void hanlpFailureFallsBackToCharactersWithoutLosingText() {
        assertEquals(List.of("甲乙丙", "丁戊己", "庚"),
                texts(failingTokenizer(3, 0).chunk("甲乙丙丁戊己庚")));
    }

    @Test
    void overlapFallsBackToTailCharactersWhenHanlpFails() {
        assertEquals(List.of("abcdefghij", "hij\n\nklmnopqrst"),
                texts(failingTokenizer(10, 3).chunk("abcdefghij\n\nklmnopqrst")));
    }

    @Test
    void overlapUsesPreviousBaseChunkAndPreservesOriginalOrder() {
        String text = "第一句。第二句。第三句。第四句。第五句。第六句。";
        assertEquals(List.of("第一句。第二句。", "第二句。\n\n第三句。第四句。", "第四句。\n\n第五句。第六句。"),
                texts(new TextChunker(8, 4, 0).chunk(text)));
    }

    @Test
    void overlapUsesCompleteTrailingSentencesWhenTheyFit() {
        assertEquals(List.of("甲。乙。丙。", "乙。丙。\n\n丁。戊。己。"),
                texts(new TextChunker(6, 4, 0).chunk("甲。乙。丙。丁。戊。己。")));
    }

    @Test
    void overlapCanExceedConfiguredBaseChunkSizeAsBefore() {
        List<String> chunks = texts(new TextChunker(8, 4, 0).chunk("第一句。第二句。第三句。第四句。"));
        assertEquals(14, chunks.get(1).length());
    }

    @Test
    void emptyTextReturnsNoFragments() {
        assertTrue(textChunker.chunk(1, "").isEmpty());
    }

    @Test
    void whitespaceTextReturnsNoFragments() {
        assertTrue(textChunker.chunk(1, " \t\n\n \r\n").isEmpty());
    }

    @Test
    void nullTextReturnsNoFragments() {
        assertTrue(textChunker.chunk(1, null).isEmpty());
    }

    @Test
    void exactChunkSizeKeepsSingleParagraphIntact() {
        assertEquals(List.of("甲乙丙丁戊"), texts(new TextChunker(5, 0, 0).chunk("甲乙丙丁戊")));
    }

    @Test
    void paragraphSeparatorCountsTowardChunkSize() {
        assertEquals(List.of("甲乙\n\n丙丁"), texts(new TextChunker(6, 0, 0).chunk("甲乙\n\n丙丁")));
        assertEquals(List.of("甲乙", "丙丁"), texts(new TextChunker(5, 0, 0).chunk("甲乙\n\n丙丁")));
    }

    @Test
    void nonPositiveChunkSizeNormalizesToOneAsBefore() {
        assertEquals(List.of("甲", "乙", "丙"), texts(failingTokenizer(0, 10).chunk("甲乙丙")));
    }

    @Test
    void minChunkSizeDoesNotForceOversizedMerge() {
        assertEquals(List.of("甲乙丙丁", "戊"), texts(new TextChunker(5, 0, 100).chunk("甲乙丙丁\n\n戊")));
    }

    @Test
    void smallChunksMergeWithinLegacyOverlapAllowance() {
        String text = "标题\n\n第一句内容较长。第二句内容较长。第三句内容较长。";
        List<String> chunks = texts(new TextChunker(20, 5, 10).chunk(text));
        assertTrue(chunks.get(0).startsWith("标题\n\n第一句内容较长。"));
        assertFalse(chunks.contains("标题"));
    }

    @Test
    void oversizedOverlapNormalizesToChunkSizeMinusOne() {
        String text = "第一句。第二句。第三句。第四句。";
        assertEquals(new TextChunker(8, 7, 0).chunk(text), new TextChunker(8, 100, 0).chunk(text));
    }

    @Test
    void nonPositiveOverlapDisablesOverlap() {
        String text = "第一句。第二句。第三句。第四句。";
        assertEquals(new TextChunker(8, 0, 0).chunk(text), new TextChunker(8, -10, 0).chunk(text));
    }

    @Test
    void anchorCompressesWhitespace() {
        TextChunkFragment chunk = textChunker.chunk(2, "  第一段\t内容\n\n第二段   内容  ").get(0);
        assertEquals("第一段 内容 第二段 内容", chunk.anchorText());
        assertTrue(chunk.text().contains("\n\n"));
    }

    @Test
    void anchorTruncatesAfter120CharactersAndAddsEllipsis() {
        TextChunkFragment chunk = textChunker.chunk(2, "甲".repeat(121)).get(0);
        assertEquals("甲".repeat(120) + "…", chunk.anchorText());
    }

    @Test
    void exactly120AnchorCharactersHaveNoEllipsis() {
        assertEquals("甲".repeat(120), textChunker.chunk("甲".repeat(120)).get(0).anchorText());
    }

    @Test
    void plainTextIsNotParserSpecificallyNormalized() {
        String text = "# Section\n公式 $x^2$\nNo. 2 / 37";
        assertEquals(text, textChunker.chunk(text).get(0).text());
        assertNull(textChunker.chunk(text).get(0).pageNumber());
    }

    private static List<String> texts(List<TextChunkFragment> fragments) {
        return fragments.stream().map(TextChunkFragment::text).toList();
    }

    private static TextChunker failingTokenizer(int chunkSize, int overlapSize) {
        return new TextChunker(chunkSize, overlapSize, 0, text -> {
            throw new IllegalStateException("HanLP unavailable");
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) throws Exception {
        Method method = TextChunker.class.getDeclaredMethod("splitTextIntoChunksWithSemantics", String.class, int.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(textChunker, text, chunkSize);
    }
}
