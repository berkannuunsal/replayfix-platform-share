package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JenkinsIncidentVersionValidation(
        UUID caseId,
        String applicationKey,
        String repositorySlug,
        Instant incidentTime,
        JenkinsBuildSnapshot buildAtIncident,
        JenkinsBuildSnapshot imageBuildAtIncident,
        String incidentVersionCommitSha,
        String jenkinsCommitSha,
        String status,
        boolean exactMatch,
        List<String> warnings
) {
}
