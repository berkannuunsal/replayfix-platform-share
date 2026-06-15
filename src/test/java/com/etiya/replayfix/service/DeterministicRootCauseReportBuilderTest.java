package com.etiya.replayfix.service;

import com.etiya.replayfix.model.AdaptiveLokiSearchResult;
import com.etiya.replayfix.model.CorrelationSignals;
import com.etiya.replayfix.model.IncidentSignals;
import com.etiya.replayfix.model.IncidentTimeline;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import com.etiya.replayfix.model.IntegrationModels.LokiLogEntry;
import com.etiya.replayfix.model.LokiSearchAttempt;
import com.etiya.replayfix.model.TempoEnrichmentResult;
import com.etiya.replayfix.model.TempoTraceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicRootCauseReportBuilderTest {

    private final DeterministicRootCauseReportBuilder builder =
            new DeterministicRootCauseReportBuilder();

    @Test
    void classifiesUnauthorizedWorkflowFailure() {
        JiraIssue jiraIssue =
                new JiraIssue(
                        "FIZZMS-8346",
                        "BAR deactivation unauthorized issue",
                        "HTTP 401 Unauthorized",
                        Map.of()
                );

        IncidentSignals signals =
                new IncidentSignals(
                        List.of(
                                "/customerorder/omintegration/complete"
                        ),
                        List.of("401"),
                        List.of("9999"),
                        List.of(
                                "BAR",
                                "RECURRING_PAYMENT_CALLBACK",
                                "Unauthorized"
                        ),
                        List.of("12"),
                        List.of(
                                "bss-backend",
                                "bss-backend-batch"
                        )
                );

        LokiLogEntry log =
                new LokiLogEntry(
                        Instant.parse(
                                "2026-06-15T10:20:00Z"
                        ),
                        "{\"app\":\"bss-backend\"}",
                        "POST /customerorder/omintegration/complete "
                                + "HTTP/1.1 401 traceId=abc123"
                );

        AdaptiveLokiSearchResult firstPass =
                new AdaptiveLokiSearchResult(
                        List.of(
                                new LokiSearchAttempt(
                                        100,
                                        "Endpoint and HTTP status",
                                        "{app=\"bss-backend\"} |= \"401\"",
                                        1,
                                        null
                                )
                        ),
                        List.of(log)
                );

        CorrelationSignals correlations =
                new CorrelationSignals(
                        List.of("abc123"),
                        List.of("order-123"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                );

        AdaptiveLokiSearchResult secondPass =
                new AdaptiveLokiSearchResult(
                        List.of(
                                new LokiSearchAttempt(
                                        100,
                                        "Trace ID correlation",
                                        "{app=~\".+\"} |= \"abc123\"",
                                        2,
                                        null
                                )
                        ),
                        List.of(log)
                );

        IncidentTimeline timeline =
                new IncidentTimeline(
                        log.timestamp(),
                        log.timestamp(),
                        1,
                        Map.of(
                                "bss-backend",
                                1
                        ),
                        Map.of(
                                "WARN",
                                1
                        ),
                        Map.of(
                                "401",
                                1
                        ),
                        List.of()
                );

        TempoEnrichmentResult tempo =
                new TempoEnrichmentResult(
                        1,
                        1,
                        List.of(
                                new TempoTraceResult(
                                        "abc123",
                                        true,
                                        null,
                                        "{}"
                                )
                        )
                );

        var report =
                builder.build(
                        jiraIssue,
                        "BAR request returned HTTP 401 Unauthorized.",
                        signals,
                        firstPass,
                        correlations,
                        secondPass,
                        timeline,
                        tempo
                );

        assertThat(report.classification())
                .isEqualTo(
                        "AUTHENTICATION_OR_AUTHORIZATION"
                );

        assertThat(report.status())
                .isEqualTo("HYPOTHESIS");

        assertThat(report.confidence())
                .isGreaterThanOrEqualTo(0.80);

        assertThat(report.probableCause())
                .contains(
                        "/customerorder/omintegration/complete"
                )
                .contains("401");

        assertThat(report.recommendedActions())
                .anyMatch(action ->
                        action.toLowerCase()
                                .contains("credential")
                );
    }
}
