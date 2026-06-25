package com.etiya.replaylab.model;

public record AiConnectionTestResult(
        boolean success,
        String model,
        long durationMs,
        String message
) {
}
