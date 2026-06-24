package com.etiya.replayfix.api.dto;

import java.util.List;
import java.util.UUID;

public record GoldenPathEvidenceSnapshotJiraPreviewResponse(
        UUID caseId,
        String jiraKey,
        String preview,
        boolean safeToPost,
        List<String> blockers,
        List<String> warnings
) {
    public GoldenPathEvidenceSnapshotJiraPreviewResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        preview = preview == null ? "" : preview;
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
