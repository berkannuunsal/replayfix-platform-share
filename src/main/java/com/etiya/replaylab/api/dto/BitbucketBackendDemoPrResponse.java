package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BitbucketBackendDemoPrResponse(
        UUID caseId,
        String defectNo,
        String defectSummary,
        boolean created,
        boolean previewOnly,
        String sourceBaseBranch,
        String targetBaseBranch,
        String bugfixBranch,
        String integrationBranch,
        String generatedFilePath,
        String commitMessage,
        String bugfixCommitSha,
        String integrationCommitSha,
        String pullRequestId,
        String pullRequestUrl,
        String title,
        List<String> blockers,
        List<String> warnings,
        Map<String, Object> branchLookupDiagnostics,
        List<String> nextActions,
        Instant generatedAt
) {
}
