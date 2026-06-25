package com.etiya.replaylab.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiInputBundleRefreshResult(
        UUID caseId,
        String bundleVersion,
        String sourceContextSource,
        String jenkinsCommitSha,
        String incidentCommitSha,
        boolean commitMismatch,
        int bundleLength,
        Map<String, Integer> sectionLengths,
        List<String> includedEvidence,
        List<String> warnings,
        AiEvidenceBundle bundle
) {
}
