package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record TempoCaseTraceCollection(
        UUID caseId,
        int candidateCount,
        int requestedTraceCount,
        int foundTraceCount,
        int failedTraceCount,
        List<TempoTraceSummary> traces,
        List<TraceCollectionFailure> failures,
        List<String> warnings
) {
}
