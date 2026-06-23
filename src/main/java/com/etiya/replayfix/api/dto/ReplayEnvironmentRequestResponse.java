package com.etiya.replayfix.api.dto;

import com.etiya.replayfix.domain.ReplayEnvironmentRequestEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReplayEnvironmentRequestResponse(
        UUID requestId,
        UUID caseId,
        String jiraKey,
        String targetKey,
        String status,
        String replayNamespace,
        String proposedHost,
        boolean dryRunOnly,
        boolean realProvisioningEnabled,
        List<String> requiredApprovals,
        List<String> blockers,
        List<String> guardrails,
        List<String> nextActions,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReplayEnvironmentRequestResponse from(
            ReplayEnvironmentRequestEntity entity,
            List<String> requiredApprovals,
            List<String> blockers,
            List<String> guardrails,
            List<String> nextActions
    ) {
        return new ReplayEnvironmentRequestResponse(
                entity.getId(),
                entity.getCaseId(),
                entity.getJiraKey(),
                entity.getTargetKey(),
                entity.getStatus(),
                entity.getReplayNamespace(),
                entity.getProposedHost(),
                entity.isDryRunOnly(),
                entity.isRealProvisioningEnabled(),
                requiredApprovals == null ? List.of() : List.copyOf(requiredApprovals),
                blockers == null ? List.of() : List.copyOf(blockers),
                guardrails == null ? List.of() : List.copyOf(guardrails),
                nextActions == null ? List.of() : List.copyOf(nextActions),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
