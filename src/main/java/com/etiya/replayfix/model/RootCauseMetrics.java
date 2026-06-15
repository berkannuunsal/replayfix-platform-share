package com.etiya.replayfix.model;

public record RootCauseMetrics(
        int firstPassQueryCount,
        int firstPassMatchedRows,
        int firstPassUniqueLogCount,
        int correlationValueCount,
        int secondPassQueryCount,
        int secondPassMatchedRows,
        int secondPassUniqueLogCount,
        int timelineEventCount,
        int tempoRequestedTraceCount,
        int tempoFoundTraceCount,
        int failedLokiQueryCount
) {
}
