package com.etiya.replaylab.model;

import java.util.UUID;

public record ApplicationDbEvidenceRequest(
        UUID caseId,
        String dataSourceKey,
        boolean execute,
        int maxRows
) {
    public ApplicationDbEvidenceRequest {
        dataSourceKey = dataSourceKey == null || dataSourceKey.isBlank()
                ? "backend"
                : dataSourceKey;
        maxRows = Math.max(1, maxRows);
    }
}
