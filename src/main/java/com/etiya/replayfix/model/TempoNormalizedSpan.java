package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;

public record TempoNormalizedSpan(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String operationName,
        String kind,
        String startTime,
        String endTime,
        long durationMicros,
        TempoSpanStatus status,
        String statusMessage,
        Map<String, String> attributes,
        List<TempoSpanEvent> events,
        boolean error,
        boolean rootSpan
) {
}
