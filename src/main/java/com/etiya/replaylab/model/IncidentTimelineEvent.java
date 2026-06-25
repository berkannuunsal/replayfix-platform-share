package com.etiya.replaylab.model;

import java.time.Instant;

public record IncidentTimelineEvent(
        Instant timestamp,
        String application,
        String searchPass,
        String severity,
        String httpMethod,
        String endpoint,
        Integer httpStatus,
        String message
) {
}
