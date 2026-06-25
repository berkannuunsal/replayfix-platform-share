package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BitbucketSingleFileDefectPrFlowResponse(
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
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
