package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record SourceWorkspacePreparationResponse(
        UUID caseId,
        String jiraKey,
        String repository,
        String repositorySlug,
        String branch,
        String workspacePath,
        boolean workspaceReady,
        boolean cloned,
        int supportedFileCount,
        List<String> warnings
) {
}
