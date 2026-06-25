package com.etiya.replaylab.model;

import java.time.Instant;
import java.util.UUID;

public record AuditEventView(
        UUID id,
        UUID caseId,
        String action,
        String actor,
        Instant createdAt,
        String details
) {
}
