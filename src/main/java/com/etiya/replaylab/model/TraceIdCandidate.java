package com.etiya.replaylab.model;

import java.util.List;

public record TraceIdCandidate(
        String traceId,
        String normalizedTraceId,
        String source,
        int confidence,
        List<String> reasons
) {
}
