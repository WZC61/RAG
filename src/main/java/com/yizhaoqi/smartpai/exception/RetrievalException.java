package com.yizhaoqi.smartpai.exception;

/** Infrastructure/permission lookup failure must never masquerade as no relevant documents. */
public class RetrievalException extends RuntimeException {
    public RetrievalException(String message) { super(message); }
    public RetrievalException(String message, Throwable cause) { super(message, cause); }
}
