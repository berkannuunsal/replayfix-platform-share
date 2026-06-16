package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record KubernetesJenkinsVersionCorrelation(
        UUID caseId,
        String applicationKey,
        String repositorySlug,
        String jenkinsCommitSha,
        Integer jenkinsBuildNumber,
        String jenkinsBuildTimestamp,
        List<String> runtimeImages,
        List<String> runtimeImageDigests,
        List<String> replicaSetRevisions,
        RuntimeVersionCorrelationStatus status,
        double confidence,
        List<String> matchedSignals,
        List<String> warnings
) {
}
