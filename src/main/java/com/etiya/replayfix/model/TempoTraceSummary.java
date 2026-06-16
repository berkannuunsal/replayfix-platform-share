package com.etiya.replayfix.model;

import java.util.List;

public record TempoTraceSummary(
        String traceId,
        String rootService,
        String rootOperation,
        String startTime,
        long totalDurationMicros,
        int spanCount,
        int serviceCount,
        int errorSpanCount,
        List<String> services,
        List<TempoServiceHop> timeline,
        List<TempoNormalizedSpan> errorSpans,
        List<String> criticalPathSpanIds,
        List<String> probableFailureServices,
        List<String> warnings
) {
}
