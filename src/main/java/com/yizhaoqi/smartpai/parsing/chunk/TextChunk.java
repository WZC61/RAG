package com.yizhaoqi.smartpai.parsing.chunk;

/** A document-level chunk; chunkIndex starts at 1 and increases across pages. */
public record TextChunk(Integer pageNumber, Integer chunkIndex, String text, String anchorText) {
}
