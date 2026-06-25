package com.etiya.replaylab.model;

public record MissingEvidenceView(
        String evidenceType,
        String severity,
        String reason,
        String expectedSource,
        String recommendedAction
) {
}
