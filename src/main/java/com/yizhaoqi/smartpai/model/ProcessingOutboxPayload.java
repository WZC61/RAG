package com.yizhaoqi.smartpai.model;

/** Durable business identity; never contains an expiring presigned URL. */
public record ProcessingOutboxPayload(String eventId, String fileMd5, String objectPath,
                                     long processingGeneration, String fileName, String requesterId) {
}
