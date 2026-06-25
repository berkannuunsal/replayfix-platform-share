package com.etiya.replaylab.api.dto;

import java.util.List;

public record PrRuleReviewRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String repositoryType,
        String ruleSourceBranch,
        String integrationBranch,
        String bugfixBranch,
        String targetBranch,
        String sourceBranch,
        String targetBaseBranch,
        String sourceBaseBranch,
        String defectKey,
        String defectSummary,
        List<ChangedFile> changedFiles,
        boolean blockPrOnViolation,
        Boolean allowRepair
) {
    public PrRuleReviewRequest(
            String requestedBy,
            String projectKey,
            String repositorySlug,
            String repositoryType,
            String targetBranch,
            String sourceBranch,
            String defectKey,
            String defectSummary,
            List<ChangedFile> changedFiles,
            boolean blockPrOnViolation
    ) {
        this(
                requestedBy,
                projectKey,
                repositorySlug,
                repositoryType,
                "",
                "",
                "",
                targetBranch,
                sourceBranch,
                "",
                "",
                defectKey,
                defectSummary,
                changedFiles,
                blockPrOnViolation,
                false
        );
    }

    public record ChangedFile(
            String path,
            String fileType,
            String language,
            List<String> added,
            List<String> removed
    ) {
    }
}
