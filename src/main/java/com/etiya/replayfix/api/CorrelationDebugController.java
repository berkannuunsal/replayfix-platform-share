package com.etiya.replayfix.api;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AdaptiveLokiSearchResult;
import com.etiya.replayfix.model.CorrelationSignals;
import com.etiya.replayfix.service.EvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class CorrelationDebugController {

    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public CorrelationDebugController(
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/correlation-summary")
    public Mono<Map<String, Object>> summary(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() -> {
            CorrelationSignals signals =
                    readLatest(
                            id,
                            EvidenceType.LOKI_CORRELATION_SIGNALS,
                            CorrelationSignals.class
                    );

            AdaptiveLokiSearchResult secondPass =
                    readLatest(
                            id,
                            EvidenceType.LOKI_SECOND_PASS,
                            AdaptiveLokiSearchResult.class
                    );

            long failedQueryCount =
                    secondPass.attempts()
                            .stream()
                            .filter(attempt ->
                                    attempt.error() != null
                                            && !attempt.error()
                                                    .isBlank()
                            )
                            .count();

            int matchedRows =
                    secondPass.attempts()
                            .stream()
                            .mapToInt(attempt ->
                                    attempt.resultCount()
                            )
                            .sum();

            Map<String, Object> response =
                    new LinkedHashMap<>();

            response.put("caseId", id);
            response.put("signals", signals);
            response.put(
                    "correlationValueCount",
                    signals.totalCount()
            );
            response.put(
                    "secondPassQueryCount",
                    secondPass.attempts().size()
            );
            response.put(
                    "failedQueryCount",
                    failedQueryCount
            );
            response.put(
                    "matchedRows",
                    matchedRows
            );
            response.put(
                    "uniqueLogCount",
                    secondPass.logs().size()
            );
            response.put(
                    "successfulAttempts",
                    secondPass.attempts()
                            .stream()
                            .filter(attempt ->
                                    attempt.resultCount() > 0
                            )
                            .toList()
            );
            response.put(
                    "sampleLogs",
                    secondPass.logs()
                            .stream()
                            .limit(10)
                            .toList()
            );

            return response;
        }).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    private <T> T readLatest(
            UUID caseId,
            EvidenceType evidenceType,
            Class<T> targetType
    ) throws Exception {

        EvidenceEntity evidence =
                evidenceService.list(caseId)
                        .stream()
                        .filter(item ->
                                item.getEvidenceType()
                                        == evidenceType
                        )
                        .reduce((first, second) -> second)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        evidenceType
                                                + " evidence not found"
                                )
                        );

        return objectMapper.readValue(
                evidence.getContentText(),
                targetType
        );
    }
}
