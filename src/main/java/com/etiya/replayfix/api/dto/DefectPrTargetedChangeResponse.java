package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DefectPrTargetedChangeResponse(
        UUID caseId,
        String defectKey,
        String defectSummary,
        boolean created,
        boolean previewOnly,
        String sourceBaseBranch,
        String targetBaseBranch,
        String bugfixBranch,
        String integrationBranch,
        String filePath,
        String changeMode,
        String commitMessage,
        String bugfixCommitSha,
        String integrationCommitSha,
        String pullRequestId,
        String pullRequestUrl,
        String title,
        boolean appliedSourceFix,
        boolean appliedRegressionTest,
        boolean appliedConfigChange,
        List<String> blockers,
        List<String> warnings,
        Map<String, Object> branchLookupDiagnostics,
        List<String> nextActions,
        Instant generatedAt
) {
}
