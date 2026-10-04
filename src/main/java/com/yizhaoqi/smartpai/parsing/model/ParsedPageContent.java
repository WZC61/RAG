package com.yizhaoqi.smartpai.parsing.model;

/** Blank text is valid for an individual page, including a figure-only page. */
public record ParsedPageContent(Integer pageNumber, String text) {}
