package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record DeterministicRootCauseRefreshResult(
        UUID caseId,
        String bundleSource,
        String reportSource,
        String previousReportSource,
        String jenkinsCommitSha,
        String incidentCommitSha,
        boolean commitMismatch,
        DeterministicRootCauseReport refreshedReport,
        DeterministicRootCauseReport previousReport,
        List<String> changes,
        List<String> warnings
) {
}
