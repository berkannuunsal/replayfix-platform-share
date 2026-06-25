package com.etiya.replaylab.api.dto;

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
        String ruleSourceBranch,
        String filePath,
        String changeMode,
        String titlePrefix,
        boolean allowReuseExistingBranches,
        Boolean allowRepair,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
    public DefectPrTargetedChangeRequest(
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
        this(
                requestedBy,
                projectKey,
                repositorySlug,
                defectKey,
                defectSummary,
                environment,
                sourceBaseBranch,
                targetBaseBranch,
                bugfixBranch,
                integrationBranch,
                "",
                filePath,
                changeMode,
                titlePrefix,
                allowReuseExistingBranches,
                true,
                confirmCreate,
                guardrailsAccepted
        );
    }
}
