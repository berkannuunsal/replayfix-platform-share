package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;

public record ReplayLabEvidenceDetail(
        String id,
        String source,
        String status,
        String summary,
        int evidenceCount,
        String confidence,
        Instant lastCheckedAt,
        String externalUrl,
        List<Field> fields,
        List<String> limitations,
        boolean safeToDisplay
) {
    public record Field(
            String name,
            String value
    ) {
    }
}
