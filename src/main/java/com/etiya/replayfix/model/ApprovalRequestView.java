package com.etiya.replayfix.model;

import com.etiya.replayfix.domain.ApprovalStatus;
import com.etiya.replayfix.domain.ApprovalTargetType;

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
