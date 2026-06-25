package com.etiya.replaylab.api.dto;

import java.util.List;

public record ReplayEnvironmentVersionContext(
        String sourceBranch,
        String incidentCommitSha,
        String buildCommitSha,
        String imageTag,
        String deploymentVersion,
        boolean versionResolved,
        List<String> missingVersionFields
) {
}
