package com.yizhaoqi.smartpai.parsing.chunk;

/** A page-local result, ready for document-level numbering. */
public record TextChunkFragment(Integer pageNumber, String text, String anchorText) {
}
