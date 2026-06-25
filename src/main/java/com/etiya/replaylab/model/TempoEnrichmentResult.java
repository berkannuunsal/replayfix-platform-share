package com.etiya.replaylab.model;

import java.util.List;

public record TempoEnrichmentResult(
        int requestedTraceCount,
        int foundTraceCount,
        List<TempoTraceResult> traces
) {
}
