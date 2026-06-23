package com.etiya.replayfix.api.dto;

public record RejectReplayEnvironmentRequest(
        String rejectedBy,
        String rejectionReason
) {
}
