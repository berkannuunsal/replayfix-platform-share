package com.etiya.replayfix.model;

import java.util.List;

public record BitbucketConnectionTestResult(
        boolean success,
        String provider,
        String projectKey,
        int repositoryCount,
        List<BitbucketRepositoryInfo> repositories,
        String message
) {
}
