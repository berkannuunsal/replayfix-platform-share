package com.etiya.replaylab.service;

import com.etiya.replaylab.integration.TempoClient;
import com.etiya.replaylab.model.CorrelationSignals;
import com.etiya.replaylab.model.TempoEnrichmentResult;
import com.etiya.replaylab.model.TempoTraceResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class TempoEnrichmentService {

    private static final int MAX_TRACE_IDS = 10;

    private final TempoClient tempoClient;

    public TempoEnrichmentService(
            TempoClient tempoClient
    ) {
        this.tempoClient = tempoClient;
    }

    public TempoEnrichmentResult enrich(
            CorrelationSignals signals,
            Instant start,
            Instant end
    ) {
        LinkedHashSet<String> traceIds =
                new LinkedHashSet<>();

        if (signals != null && signals.traceIds() != null) {
            traceIds.addAll(signals.traceIds());
        }

        List<TempoTraceResult> results =
                new ArrayList<>();

        traceIds.stream()
                .filter(value ->
                        value != null
                                && !value.isBlank()
                )
                .limit(MAX_TRACE_IDS)
                .forEach(traceId ->
                        results.add(
                                tempoClient.getTrace(
                                        traceId,
                                        start,
                                        end
                                )
                        )
                );

        int foundCount =
                (int) results.stream()
                        .filter(TempoTraceResult::found)
                        .count();

        return new TempoEnrichmentResult(
                traceIds.size(),
                foundCount,
                results
        );
    }
}
