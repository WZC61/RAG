package com.yizhaoqi.smartpai.parsing.pp;

public record PpJobStatus(String jobId, String state, String errorMsg,
                          String resultJsonUrl, String markdownUrl,
                          Integer totalPages, Integer extractedPages) {}
