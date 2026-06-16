package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;

public record JenkinsBuildSnapshot(
        String jobName,
        Integer buildNumber,
        String result,
        Long timestamp,
        Long duration,
        String url,
        String commitSha,
        String commitMessage,
        String metadataSource,
        TestSummary testSummary,
        Map<String, String> parameters,
        List<Artifact> artifacts
) {

    public record TestSummary(
            int testsRun,
            int failures,
            int errors,
            int skipped
    ) {
    }

    public record Artifact(
            String fileName,
            String relativePath
    ) {
    }
}
