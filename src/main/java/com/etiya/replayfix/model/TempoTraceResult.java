package com.etiya.replayfix.model;

public record TempoTraceResult(
        String traceId,
        boolean found,
        String error,
        String rawJson
) {
}
