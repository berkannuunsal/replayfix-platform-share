package com.etiya.replaylab.model;

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
