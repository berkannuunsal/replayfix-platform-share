package com.etiya.replaylab.api.dto;

public record ApproveReplayEnvironmentRequest(
        String approvedBy,
        String approvalNote,
        boolean acceptGuardrails
) {
}
