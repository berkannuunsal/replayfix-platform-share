package com.etiya.replaylab.model;

public record TempoTraceResult(
        String traceId,
        boolean found,
        String error,
        String rawJson
) {
}
