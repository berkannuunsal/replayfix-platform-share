package com.etiya.replayfix.model;

import java.util.List;

public record DashboardEvidenceCard(
        String source,
        String status,
        String keyFinding,
        String confidence,
        int evidenceCount,
        String lastCollectedAt,
        List<String> warnings
) {
}
