package com.etiya.replayfix.model;

public record FixPlanEvidenceReference(
        String evidenceType,
        String source,
        String reference,
        String status,
        String summary
) {
    public FixPlanEvidenceReference {
        evidenceType = evidenceType == null ? "" : evidenceType;
        source = source == null ? "" : source;
        reference = reference == null ? "" : reference;
        status = status == null ? "HYPOTHESIS" : status;
        summary = summary == null ? "" : summary;
    }
}
