package com.etiya.replayfix.api;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AdaptiveLokiSearchResult;
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
public class LokiDebugController {

    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public LokiDebugController(
        EvidenceService evidenceService,
        ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/loki-summary")
    public Mono<Map<String, Object>> summary(
        @PathVariable UUID id
    ) {
        return Mono.fromCallable(() -> {

            EvidenceEntity evidence = evidenceService.list(id)
                .stream()
                .filter(item ->
                    item.getEvidenceType()
                        == EvidenceType.LOKI_LOG
                )
                .reduce((first, second) -> second)
                .orElseThrow(() ->
                    new IllegalArgumentException(
                        "LOKI_LOG evidence not found for case: "
                            + id
                    )
                );

            AdaptiveLokiSearchResult result =
                objectMapper.readValue(
                    evidence.getContentText(),
                    AdaptiveLokiSearchResult.class
                );

            int totalMatchedRows = result.attempts()
                .stream()
                .mapToInt(attempt -> attempt.resultCount())
                .sum();

            long failedQueryCount = result.attempts()
                .stream()
                .filter(attempt -> attempt.error() != null)
                .count();

            var failedAttempts = result.attempts()
                    .stream()
                    .filter(attempt ->
                            attempt.error() != null
                                    && !attempt.error().isBlank()
                    )
                    .toList();

            var successfulAttempts = result.attempts()
                .stream()
                .filter(attempt -> attempt.resultCount() > 0)
                .toList();

            var sampleLogs = result.logs()
                .stream()
                .limit(10)
                .toList();

            Map<String, Object> response =
                new LinkedHashMap<>();

            response.put("caseId", id);
            response.put(
                "queryCount",
                result.attempts().size()
            );
            response.put(
                "failedQueryCount",
                failedQueryCount
            );
            response.put(
                "totalMatchedRows",
                totalMatchedRows
            );
            response.put(
                "uniqueLogCount",
                result.logs().size()
            );
            response.put(
                "successfulAttempts",
                successfulAttempts
            );
            response.put(
                "failedAttempts",
                failedAttempts
            );
            response.put(
                "firstError",
                failedAttempts.isEmpty()
                        ? ""
                        : failedAttempts.get(0).error()
            );
            response.put(
                "sampleLogs",
                sampleLogs
            );

            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
