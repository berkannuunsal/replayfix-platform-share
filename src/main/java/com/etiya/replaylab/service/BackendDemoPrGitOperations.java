package com.etiya.replaylab.service;

import java.util.List;

public interface BackendDemoPrGitOperations {

    BackendDemoPrGitResult commitPushAndPrepareIntegrationBranch(
            String projectKey,
            String repositorySlug,
            String sourceBaseBranch,
            String targetBaseBranch,
            String bugfixBranch,
            String integrationBranch,
            boolean bugfixBranchExists,
            boolean integrationBranchExists,
            String generatedFilePath,
            String generatedFileContent,
            String commitMessage
    );

    record BackendDemoPrGitResult(
            boolean pushed,
            boolean mergeAttempted,
            boolean mergeSucceeded,
            boolean mergeConflict,
            String bugfixCommitSha,
            String integrationCommitSha,
            List<String> conflictedFiles,
            String warning,
            String error
    ) {
        public BackendDemoPrGitResult(
                boolean pushed,
                boolean mergeAttempted,
                boolean mergeSucceeded,
                boolean mergeConflict,
                String bugfixCommitSha,
                String integrationCommitSha,
                String warning,
                String error
        ) {
            this(
                    pushed,
                    mergeAttempted,
                    mergeSucceeded,
                    mergeConflict,
                    bugfixCommitSha,
                    integrationCommitSha,
                    List.of(),
                    warning,
                    error
            );
        }
    }
}
