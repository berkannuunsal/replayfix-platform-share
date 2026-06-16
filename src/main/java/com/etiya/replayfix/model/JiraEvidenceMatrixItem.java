package com.etiya.replayfix.model;

import com.etiya.replayfix.domain.EvidenceAvailability;

public record JiraEvidenceMatrixItem(
        String source,
        EvidenceAvailability status,
        String keyFinding,
        String confidence,
        String evidenceType,
        String evidenceSource,
        String evidenceId
) {
}
