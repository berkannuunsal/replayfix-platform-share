package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.integration.TempoClient;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TempoCaseTraceCollectionService {

    private static final String TRACE_SOURCE = "tempo-trace";
    private static final String SUMMARY_SOURCE = "tempo-trace-collection-summary";
    private static final int MAX_TRACES_PER_CASE = 5;
    private static final int MAX_SPANS_PER_TRACE = 5000;

    private final TempoClient tempoClient;
    private final TraceIdCandidateExtractor candidateExtractor;
    private final TempoTraceParser traceParser;
    private final TempoTraceTimelineBuilder timelineBuilder;
    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public TempoCaseTraceCollectionService(
            TempoClient tempoClient,
            TraceIdCandidateExtractor candidateExtractor,
            TempoTraceParser traceParser,
            TempoTraceTimelineBuilder timelineBuilder,
            EvidenceService evidenceService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.tempoClient = tempoClient;
        this.candidateExtractor = candidateExtractor;
        this.traceParser = traceParser;
        this.timelineBuilder = timelineBuilder;
        this.evidenceService = evidenceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public TempoCaseTraceCollection collect(UUID caseId) {
        List<EvidenceEntity> evidence = evidenceService.list(caseId);

        List<TraceIdCandidate> candidates = candidateExtractor.extract(caseId, evidence);

        int candidateCount = candidates.size();
        int requestedTraceCount = Math.min(candidateCount, MAX_TRACES_PER_CASE);

        List<TempoTraceSummary> traces = new ArrayList<>();
        List<TraceCollectionFailure> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < requestedTraceCount; i++) {
            TraceIdCandidate candidate = candidates.get(i);

            try {
                TempoRawTrace rawTrace = tempoClient.fetchTrace(
                        candidate.normalizedTraceId()
                );

                if (!rawTrace.found()) {
                    failures.add(new TraceCollectionFailure(
                            candidate.traceId(),
                            "NOT_FOUND",
                            rawTrace.httpStatus(),
                            "Trace not found in Tempo"
                    ));
                    continue;
                }

                List<TempoNormalizedSpan> spans = traceParser.parse(
                        candidate.normalizedTraceId(),
                        rawTrace.rawJson(),
                        MAX_SPANS_PER_TRACE
                );

                TempoTraceSummary summary = timelineBuilder.build(
                        candidate.normalizedTraceId(),
                        spans
                );

                traces.add(summary);

                saveTraceEvidence(caseId, rawTrace);

            } catch (Exception exception) {
                failures.add(new TraceCollectionFailure(
                        candidate.traceId(),
                        "PARSE_ERROR",
                        null,
                        exception.getMessage()
                ));
            }
        }

        TempoCaseTraceCollection collection = new TempoCaseTraceCollection(
                caseId,
                candidateCount,
                requestedTraceCount,
                traces.size(),
                failures.size(),
                traces,
                failures,
                warnings
        );

        saveSummaryEvidence(caseId, collection);

        auditService.record(
                caseId,
                "TEMPO_TRACE_COLLECTION_COMPLETED",
                "replayfix-platform",
                "candidates=" + candidateCount
                        + ", requested=" + requestedTraceCount
                        + ", found=" + traces.size()
                        + ", failed=" + failures.size()
        );

        return collection;
    }

    private void saveTraceEvidence(UUID caseId, TempoRawTrace rawTrace) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.TEMPO_TRACE,
                    TRACE_SOURCE,
                    rawTrace.rawJson(),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Tempo trace evidence.",
                    exception
            );
        }
    }

    private void saveSummaryEvidence(
            UUID caseId,
            TempoCaseTraceCollection collection
    ) {
        try {
            String json = objectMapper.writeValueAsString(collection);

            evidenceService.save(
                    caseId,
                    EvidenceType.TEMPO_TRACE,
                    SUMMARY_SOURCE,
                    json,
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Tempo trace collection summary.",
                    exception
            );
        }
    }
}
