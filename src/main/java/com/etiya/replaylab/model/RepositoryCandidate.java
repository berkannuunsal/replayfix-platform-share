package com.etiya.replaylab.model;

import java.util.List;

public record RepositoryCandidate(
        String projectKey,
        String slug,
        String name,
        String defaultBranch,
        String cloneUrl,
        int score,
        List<String> reasons
) {
}
