package com.yizhaoqi.smartpai.parsing.persistence;

/** Values from the legacy FileUpload adapter; neither content identity nor aggregate ACL. */
public record LegacyPermissionContext(String userId, String orgTag, boolean isPublic) {
    public LegacyPermissionContext {
        if (userId == null || userId.isBlank() || userId.length() > 64)
            throw new IllegalArgumentException("Legacy userId must be supplied from FileUpload");
        if (orgTag != null && orgTag.length() > 50)
            throw new IllegalArgumentException("Legacy orgTag exceeds its database limit");
    }
}
