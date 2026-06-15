package com.etiya.replayfix.model;

import java.util.List;

public record RepositoryResolutionResult(
        String projectKey,
        String primaryRepositorySlug,
        List<RepositoryCandidate> candidates,
        List<String> unresolvedSignals,
        String warning
) {
    public boolean hasSelection() {
        return primaryRepositorySlug != null
                && !primaryRepositorySlug.isBlank();
    }
}
