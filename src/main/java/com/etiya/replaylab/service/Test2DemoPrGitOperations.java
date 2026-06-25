package com.etiya.replaylab.service;

public interface Test2DemoPrGitOperations {

    Test2DemoPrGitResult commitAndPushIntegrationBranch(
            String projectKey,
            String repositorySlug,
            String targetBranch,
            String integrationBranch,
            String generatedFilePath,
            String generatedFileContent,
            String commitMessage
    );

    record Test2DemoPrGitResult(
            boolean pushed,
            String commitSha,
            String warning,
            String error
    ) {
    }
}
