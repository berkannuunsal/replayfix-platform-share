package com.etiya.replayfix.service;

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
            String warning,
            String error
    ) {
    }
}
