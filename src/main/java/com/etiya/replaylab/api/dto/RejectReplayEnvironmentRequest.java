package com.etiya.replaylab.api.dto;

public record RejectReplayEnvironmentRequest(
        String rejectedBy,
        String rejectionReason
) {
}
