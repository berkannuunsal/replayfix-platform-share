package com.etiya.replaylab.model;

public record DashboardPolicyView(
        boolean allowJiraCommentWrite,
        boolean allowGeneratedCodeWrite,
        boolean allowTestExecution,
        boolean allowGitPush,
        boolean allowPrCreation
) {
}
