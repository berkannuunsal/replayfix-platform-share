package com.etiya.replayfix.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record CreateCaseRequest(
        @NotBlank String jiraKey,
        @NotBlank String targetKey,
        String orderId,
        String traceId,
        Instant incidentTime,
        String sourceBranch,
        String sourceCommit,
        String imageTag
) {
}
