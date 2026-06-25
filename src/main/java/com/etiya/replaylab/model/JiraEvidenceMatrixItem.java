package com.etiya.replaylab.model;

import com.etiya.replaylab.domain.EvidenceAvailability;

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
