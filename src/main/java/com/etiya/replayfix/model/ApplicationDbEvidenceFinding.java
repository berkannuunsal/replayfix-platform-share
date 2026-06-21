package com.etiya.replayfix.model;

import java.util.List;

public record ApplicationDbEvidenceFinding(
        String findingType,
        String templateId,
        String summary,
        String status,
        double confidence,
        List<String> relatedSignals
) {
    public ApplicationDbEvidenceFinding {
        findingType = findingType == null ? "UNKNOWN" : findingType;
        templateId = templateId == null ? "UNKNOWN" : templateId;
        summary = summary == null ? "" : summary;
        status = status == null ? "HYPOTHESIS" : status;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        relatedSignals = relatedSignals == null ? List.of() : List.copyOf(relatedSignals);
    }
}
