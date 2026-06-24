package com.etiya.replayfix.api.dto;

public record GuardedFixDemoPreviewRequest(
        String requestedBy,
        String jiraKey,
        String projectKey,
        String repositorySlug,
        String developmentBaseBranch,
        String environmentTargetBranch,
        String bugfixBranch,
        String integrationBranch,
        String targetPrBranch,
        Boolean preferTestOnlyPrWhenSourceFixNotApproved,
        Boolean includeCodeAdvisory,
        Boolean includePatchPlan,
        Boolean includeRegressionTestDraft,
        Boolean includeApprovedWritePlan,
        Boolean includeWorkspaceWrite,
        Boolean includeWorkspacePush,
        Boolean includePrPreview
) {
}
