package com.etiya.replayfix.api.dto;

public record ApproveReplayEnvironmentRequest(
        String approvedBy,
        String approvalNote,
        boolean acceptGuardrails
) {
}
