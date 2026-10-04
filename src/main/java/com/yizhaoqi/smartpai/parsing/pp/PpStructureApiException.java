package com.yizhaoqi.smartpai.parsing.pp;

import lombok.Getter;

/** No raw HTTP/Jackson cause: these can contain credentials or signed URLs. */
@Getter
public class PpStructureApiException extends RuntimeException {
    public enum Stage { SUBMIT, STATUS, POLL, RESULT_DOWNLOAD, RESULT_DECODE }

    private final Stage stage;
    private final Integer httpStatus;
    private final Integer businessCode;
    private final String traceId;
    private final String jobId;

    public PpStructureApiException(Stage stage, Integer httpStatus, Integer businessCode,
                                   String traceId, String jobId, String message) {
        super(message);
        this.stage = stage;
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
        this.traceId = traceId;
        this.jobId = jobId;
    }
}
