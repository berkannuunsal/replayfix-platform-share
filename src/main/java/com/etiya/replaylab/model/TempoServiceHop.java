package com.etiya.replaylab.model;

import java.util.List;

public record TempoServiceHop(
        int order,
        String serviceName,
        String operationName,
        String spanId,
        String parentSpanId,
        long durationMicros,
        String status,
        boolean error,
        List<String> signals
) {
}
