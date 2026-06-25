package com.etiya.replayfix.api.dto;

public record DefectPrTargetedChangeRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String defectKey,
        String defectSummary,
        String environment,
        String sourceBaseBranch,
        String targetBaseBranch,
        String bugfixBranch,
        String integrationBranch,
        String filePath,
        String changeMode,
        String titlePrefix,
        boolean allowReuseExistingBranches,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
