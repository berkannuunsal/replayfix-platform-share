package com.etiya.replayfix.model;

import java.util.List;

public record JenkinsJobSnapshot(
        String name,
        String url,
        boolean buildable,
        boolean inQueue,
        Integer nextBuildNumber,
        BuildSummary lastBuild,
        BuildSummary lastSuccessfulBuild,
        BuildSummary lastFailedBuild,
        List<ParameterDefinition> parameters
) {
    public record BuildSummary(
            Integer number,
            String result,
            Long timestamp,
            Long duration,
            String url
    ) {
    }

    public record ParameterDefinition(
            String name,
            String type,
            String defaultValue
    ) {
    }
}
