package com.etiya.replayfix.model;

public record BitbucketRepositoryInfo(
        String projectKey,
        String slug,
        String name,
        String defaultBranch,
        String state,
        boolean archived,
        String cloneUrl
) {
}
