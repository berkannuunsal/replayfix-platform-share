package com.etiya.replaylab.model;

public record JenkinsConnectionTestResult(
        boolean success,
        String authenticatedUser,
        String baseUrl,
        String message
) {
}
