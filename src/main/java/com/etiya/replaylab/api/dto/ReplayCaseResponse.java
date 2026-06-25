package com.etiya.replaylab.api.dto;

import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import java.time.Instant;
import java.util.UUID;

public record ReplayCaseResponse(
        UUID id,
        String jiraKey,
        String targetKey,
        String orderId,
        String traceId,
        Instant incidentTime,
        ReplayCaseStatus status,
        String sourceBranch,
        String sourceCommit,
        String imageTag,
        String namespace,
        String generatedBranch,
        String pullRequestUrl,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReplayCaseResponse from(ReplayCaseEntity entity) {
        return new ReplayCaseResponse(
                entity.getId(), entity.getJiraKey(), entity.getTargetKey(),
                entity.getOrderId(), entity.getTraceId(), entity.getIncidentTime(),
                entity.getStatus(), entity.getSourceBranch(), entity.getSourceCommit(),
                entity.getImageTag(), entity.getNamespace(), entity.getGeneratedBranch(),
                entity.getPullRequestUrl(), entity.getLastError(),
                entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }
}
