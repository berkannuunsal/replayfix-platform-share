package com.etiya.replaylab.service;

import com.etiya.replaylab.integration.LokiClient;
import com.etiya.replaylab.model.AdaptiveLokiSearchResult;
import com.etiya.replaylab.model.IntegrationModels.LokiLogEntry;
import com.etiya.replaylab.model.LokiQueryCandidate;
import com.etiya.replaylab.model.LokiQueryPlan;
import com.etiya.replaylab.model.LokiSearchAttempt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdaptiveLokiSearchService {

    private final LokiClient lokiClient;

    public AdaptiveLokiSearchService(LokiClient lokiClient) {
        this.lokiClient = lokiClient;
    }

    public AdaptiveLokiSearchResult search(
        LokiQueryPlan plan,
        Instant start,
        Instant end,
        int perQueryLimit,
        int maxTotalLogs
    ) {
        return search(
                plan.queries(),
                start,
                end,
                perQueryLimit,
                maxTotalLogs
        );
    }

    public AdaptiveLokiSearchResult search(
        List<LokiQueryCandidate> queryCandidates,
        Instant start,
        Instant end,
        int perQueryLimit,
        int maxTotalLogs
    ) {
        List<LokiSearchAttempt> attempts =
                new ArrayList<>();

        Map<String, LokiLogEntry> uniqueLogs =
                new LinkedHashMap<>();

        List<LokiQueryCandidate> orderedQueries =
                queryCandidates.stream()
                        .sorted(
                                Comparator.comparingInt(
                                        LokiQueryCandidate::priority
                                ).reversed()
                        )
                        .toList();

        for (LokiQueryCandidate candidate : orderedQueries) {

            if (uniqueLogs.size() >= maxTotalLogs) {
                break;
            }

            // Güçlü sorgular yeterli log bulduysa
            // BAR, payment gibi daha genel sorguları çalıştırma.
            if (uniqueLogs.size() >= 150
                    && candidate.priority() < 80) {
                break;
            }

            int remaining =
                maxTotalLogs - uniqueLogs.size();

            int currentLimit =
                Math.min(perQueryLimit, remaining);

            try {
                List<LokiLogEntry> logs =
                    lokiClient.queryRange(
                        candidate.logQl(),
                        start,
                        end,
                        currentLimit
                    );

                for (LokiLogEntry log : logs) {
                    uniqueLogs.putIfAbsent(
                        createLogKey(log),
                        log
                    );
                }

                attempts.add(
                    new LokiSearchAttempt(
                        candidate.priority(),
                        candidate.reason(),
                        candidate.logQl(),
                        logs.size(),
                        null
                    )
                );

            } catch (Exception exception) {
                attempts.add(
                    new LokiSearchAttempt(
                        candidate.priority(),
                        candidate.reason(),
                        candidate.logQl(),
                        0,
                        rootCauseMessage(exception)
                    )
                );
            }
        }

        return new AdaptiveLokiSearchResult(
            attempts,
            new ArrayList<>(uniqueLogs.values())
        );
    }

    private String createLogKey(LokiLogEntry log) {
        return log.timestamp()
            + "|"
            + log.labels()
            + "|"
            + log.line();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass().getSimpleName()
            + ": "
            + root.getMessage();
    }
}
