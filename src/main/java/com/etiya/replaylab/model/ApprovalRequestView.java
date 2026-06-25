package com.etiya.replaylab.model;

import com.etiya.replaylab.domain.ApprovalStatus;
import com.etiya.replaylab.domain.ApprovalTargetType;

import java.time.Instant;
import java.util.UUID;

public record ApprovalRequestView(
        UUID id,
        UUID caseId,
        ApprovalTargetType targetType,
        UUID targetEvidenceId,
        String targetEvidenceType,
        String targetEvidenceSource,
        ApprovalStatus status,
        String requestedBy,
        String requestComment,
        Instant requestedAt,
        String decidedBy,
        String decisionComment,
        Instant decidedAt,
        boolean allowsGeneratedTestWrite,
        boolean allowsTestExecution,
        boolean allowsPatternInformedTestWrite
) {
}
