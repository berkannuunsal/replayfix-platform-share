package com.etiya.replayfix.model;

public record AiConnectionTestResult(
        boolean success,
        String model,
        long durationMs,
        String message
) {
}
