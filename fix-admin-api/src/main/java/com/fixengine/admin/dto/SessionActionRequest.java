package com.fixengine.admin.dto;

import jakarta.validation.constraints.Min;

/**
 * Request DTO for session actions that require parameters.
 */
public class SessionActionRequest {

    @Min(value = 1, message = "Sequence number must be at least 1")
    private Integer seqNum;

    private String reason;

    public SessionActionRequest() {}

    public Integer getSeqNum() {
        return seqNum;
    }

    public void setSeqNum(Integer seqNum) {
        this.seqNum = seqNum;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
