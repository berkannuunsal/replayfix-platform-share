package com.etiya.replaylab.api.dto;

import java.time.Instant;

public record ReplayLabAgentEvent(
        Instant timestamp,
        int elapsedSeconds,
        String category,
        String status,
        String message,
        String details,
        boolean safeToDisplay
) {
}
