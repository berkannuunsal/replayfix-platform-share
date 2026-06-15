package com.etiya.replayfix.model;

public record JenkinsConnectionTestResult(
        boolean success,
        String authenticatedUser,
        String baseUrl,
        String message
) {
}
