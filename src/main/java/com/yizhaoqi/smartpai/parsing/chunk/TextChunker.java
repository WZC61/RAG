package com.yizhaoqi.smartpai.parsing.chunk;

import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser-independent legacy semantic chunking. Fragments have no document-level index. */
public final class TextChunker {
    private static final Logger logger = LoggerFactory.getLogger(TextChunker.class);

    private final int chunkSize;
    private final int overlapSize;
    private final int minChunkSize;
    private final Function<String, List<Term>> tokenizer;

    public TextChunker(int chunkSize, int overlapSize, int minChunkSize) {
        this(chunkSize, overlapSize, minChunkSize, StandardTokenizer::segment);
    }

    // Package-local seam for deterministic tokenizer failure tests.
    TextChunker(int chunkSize, int overlapSize, int minChunkSize, Function<String, List<Term>> tokenizer) {
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;
        this.minChunkSize = minChunkSize;
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    public List<TextChunkFragment> chunk(String text) {
        return chunk(null, text);
    }

    /** Page identity is metadata; each invocation has its own overlap boundary. */
    public List<TextChunkFragment> chunk(Integer pageNumber, String text) {
        return splitTextIntoChunksWithSemantics(text, chunkSize).stream()
                .map(value -> new TextChunkFragment(pageNumber, value, buildAnchorText(value)))
                .toList();
    }

    private String buildAnchorText(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }

        String normalized = chunk.replaceAll("\\s+", " ").trim();
        int maxLength = 120;
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    /**
     * 智能文本分割，保持语义完整性
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        int effectiveChunkSize = Math.max(1, chunkSize);
        List<String> baseChunks = splitTextIntoBaseChunks(text, effectiveChunkSize);
        List<String> mergedChunks = mergeSmallChunks(baseChunks, effectiveChunkSize);
        return addSemanticOverlap(mergedChunks, effectiveChunkSize);
    }

    private List<String> splitTextIntoBaseChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按段落分割
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph == null || paragraph.isBlank()) {
                continue;
            }

            paragraph = paragraph.trim();

            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                chunks.addAll(sentenceChunks);
            }
            // 如果添加这个段落会超过chunk大小
            else if (currentChunk.length() + paragraph.length() + paragraphSeparatorLength(currentChunk) > chunkSize) {
                // 保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                // 开始新chunk
                currentChunk = new StringBuilder(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private int paragraphSeparatorLength(StringBuilder currentChunk) {
        return currentChunk.length() > 0 ? 2 : 0;
    }

    private List<String> mergeSmallChunks(List<String> chunks, int chunkSize) {
        List<String> merged = new ArrayList<>();
        int effectiveMinChunkSize = normalizedMinChunkSize(chunkSize);
        int maxMergedChunkSize = chunkSize + normalizedOverlapSize(chunkSize);

        for (String chunk : chunks) {
            String normalizedChunk = normalizeChunk(chunk);
            if (normalizedChunk.isEmpty()) {
                continue;
            }

            if (!merged.isEmpty()) {
                String previous = merged.get(merged.size() - 1);
                String combined = combineChunks(previous, normalizedChunk);
                if ((normalizedChunk.length() < effectiveMinChunkSize || previous.length() < effectiveMinChunkSize)
                        && combined.length() <= maxMergedChunkSize) {
                    merged.set(merged.size() - 1, combined);
                    continue;
                }
            }

            merged.add(normalizedChunk);
        }

        return merged;
    }

    private String normalizeChunk(String chunk) {
        return chunk == null ? "" : chunk.trim();
    }

    private int normalizedMinChunkSize(int chunkSize) {
        if (minChunkSize <= 0) {
            return 0;
        }
        return Math.min(minChunkSize, chunkSize);
    }

    private int normalizedOverlapSize(int chunkSize) {
        if (overlapSize <= 0 || chunkSize <= 1) {
            return 0;
        }
        return Math.min(overlapSize, chunkSize - 1);
    }

    private String combineChunks(String first, String second) {
        if (first == null || first.isBlank()) {
            return normalizeChunk(second);
        }
        if (second == null || second.isBlank()) {
            return normalizeChunk(first);
        }
        return normalizeChunk(first) + "\n\n" + normalizeChunk(second);
    }

    private List<String> addSemanticOverlap(List<String> chunks, int chunkSize) {
        int effectiveOverlapSize = normalizedOverlapSize(chunkSize);
        if (effectiveOverlapSize <= 0 || chunks.size() <= 1) {
            return chunks;
        }

        List<String> overlappedChunks = new ArrayList<>(chunks.size());
        overlappedChunks.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String overlapText = buildOverlapText(chunks.get(i - 1), effectiveOverlapSize);
            String currentChunk = chunks.get(i);
            if (overlapText.isEmpty()) {
                overlappedChunks.add(currentChunk);
            } else {
                overlappedChunks.add(overlapText + "\n\n" + currentChunk);
            }
        }

        return overlappedChunks;
    }

    private String buildOverlapText(String text, int maxLength) {
        if (text == null || text.isBlank() || maxLength <= 0) {
            return "";
        }

        List<String> sentences = splitIntoSentenceUnits(text);
        StringBuilder overlap = new StringBuilder();

        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i).trim();
            if (sentence.isEmpty()) {
                continue;
            }

            if (sentence.length() > maxLength) {
                return overlap.isEmpty()
                        ? tailByTokenBoundary(sentence, maxLength)
                        : overlap.toString().trim();
            }

            if (overlap.length() + sentence.length() > maxLength) {
                break;
            }

            overlap.insert(0, sentence);
        }

        if (overlap.isEmpty()) {
            return tailByTokenBoundary(text, maxLength);
        }
        return overlap.toString().trim();
    }

    private List<String> splitIntoSentenceUnits(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = Pattern.compile("[^。！？；.!?;]+[。！？；.!?;]?").matcher(text);
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        if (sentences.isEmpty()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    private String tailByTokenBoundary(String text, int maxLength) {
        if (text == null || text.isBlank() || maxLength <= 0) {
            return "";
        }

        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        try {
            List<Term> termList = tokenizer.apply(normalized);
            StringBuilder tail = new StringBuilder();
            for (int i = termList.size() - 1; i >= 0; i--) {
                String word = termList.get(i).word;
                if (word == null || word.isEmpty()) {
                    continue;
                }
                if (tail.length() + word.length() > maxLength) {
                    break;
                }
                tail.insert(0, word);
            }

            if (!tail.isEmpty()) {
                return tail.toString();
            }
        } catch (Exception e) {
            logger.debug("HanLP overlap 边界处理失败，使用字符兜底: {}", e.getMessage());
        }

        return normalized.substring(Math.max(0, normalized.length() - maxLength));
    }

    /**
     * 分割长段落，按句子边界
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 如果单个句子太长，按词分割
                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        
        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = tokenizer.apply(sentence);
            
            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;
                
                // 如果添加这个词会超过chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                
                currentChunk.append(word);
            }
            
            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }
            
            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}", 
                    sentence.length(), termList.size(), chunks.size());
                    
        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
         }
        
        return chunks;
    }
    
    /**
     * 备用方案：按字符分割
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

}
