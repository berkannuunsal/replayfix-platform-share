package com.etiya.replayfix.api.dto;

import java.util.List;

public record PrOutcomeSummaryRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String pullRequestId,
        String pullRequestUrl,
        String defectKey,
        String defectSummary,
        String sourceBaseBranch,
        String targetBaseBranch,
        String bugfixBranch,
        String integrationBranch,
        String changeMode,
        String filePath,
        String bugfixCommitSha,
        String integrationCommitSha,
        String reviewStatus,
        int blockerViolationCount,
        List<String> rulesLoaded,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
