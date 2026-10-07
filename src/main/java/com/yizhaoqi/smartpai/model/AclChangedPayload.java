package com.yizhaoqi.smartpai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/** Only a reconciliation trigger: never persist an ACL snapshot or a signed URL. */
public record AclChangedPayload(String eventId, String fileMd5, String deletedUserId) {
    public static ProcessingOutbox event(ObjectMapper mapper, String md5, String deletedUserId) {
        String id = FileProcessingTask.TASK_TYPE_ACL_CHANGED + ":" + UUID.randomUUID();
        ProcessingOutbox event = new ProcessingOutbox();
        event.setEventId(id);
        event.setFileMd5(md5);
        event.setEventType(FileProcessingTask.TASK_TYPE_ACL_CHANGED);
        event.setUserId(deletedUserId); // Cleanup hint only, not the permission identity.
        try { event.setPayload(mapper.writeValueAsString(new AclChangedPayload(id, md5, deletedUserId))); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize ACL reconciliation event", failure); }
        return event;
    }
}
