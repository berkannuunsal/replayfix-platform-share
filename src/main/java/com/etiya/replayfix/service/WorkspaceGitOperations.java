package com.etiya.replayfix.service;

import java.nio.file.Path;

public interface WorkspaceGitOperations {
    WorkspaceGitStatus status(Path workspaceRoot);

    WorkspaceGitPushResult pushApprovedChanges(
            Path workspaceRoot,
            String developmentBaseBranch,
            String environmentTargetBranch,
            String bugfixBranch,
            String integrationBranch,
            String commitMessage
    );

    record WorkspaceGitStatus(
            boolean gitRepository,
            boolean hasChanges,
            String commitSha,
            String safeSummary,
            String error
    ) {
    }

    record WorkspaceGitPushResult(
            boolean bugfixBranchPushed,
            boolean integrationBranchPrepared,
            boolean mergeAttempted,
            boolean mergeSucceeded,
            boolean mergeConflict,
            String commitSha,
            String warning,
            String error
    ) {
    }
}
