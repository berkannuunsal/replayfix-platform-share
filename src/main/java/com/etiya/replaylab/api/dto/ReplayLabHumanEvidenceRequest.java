package com.etiya.replaylab.api.dto;

public record ReplayLabHumanEvidenceRequest(
        String sourceType,
        String title,
        String notes,
        String url
) {
}
