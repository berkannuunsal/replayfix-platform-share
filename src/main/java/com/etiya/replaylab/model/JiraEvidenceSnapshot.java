package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record JiraEvidenceSnapshot(
        UUID caseId,
        String issueKey,
        String analysisId,
        String workflowStatus,
        String generatedAt,
        String businessImpact,
        String technicalSymptom,
        String affectedFlow,
        List<JiraEvidenceMatrixItem> evidenceMatrix,
        List<String> probableFailureChain,
        String probableRootCause,
        double rootCauseConfidence,
        List<String> competingHypotheses,
        List<String> regressionTestHypothesis,
        List<String> minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction,
        List<String> warnings
) {
}
