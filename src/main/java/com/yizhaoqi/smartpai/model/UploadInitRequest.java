package com.yizhaoqi.smartpai.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** File-level upload metadata. The authenticated user is supplied separately. */
public record UploadInitRequest(
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{32}") String fileMd5,
        @NotBlank @Size(max = 255) String fileName,
        @Positive long totalSize,
        @Positive Integer totalChunks,
        String orgTag,
        boolean isPublic) {
}
