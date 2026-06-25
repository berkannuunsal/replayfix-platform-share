package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record RealActionsSummaryResponse(
        UUID caseId,
        String jiraKey,
        boolean realActionsEnabled,
        JiraTestTask jiraTestTask,
        BitbucketBranchFlow bitbucketBranchFlow,
        BitbucketPullRequest bitbucketPullRequest,
        List<String> guardrails,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions
) {
    public record JiraTestTask(
            boolean created,
            String issueKey,
            String url,
            String status
    ) {
    }

    public record BitbucketBranchFlow(
            boolean executed,
            String bugfixBranch,
            String integrationBranch,
            boolean mergeSucceeded,
            String status
    ) {
    }

    public record BitbucketPullRequest(
            boolean created,
            String url,
            String sourceBranch,
            String targetBranch,
            String status
    ) {
    }
}
