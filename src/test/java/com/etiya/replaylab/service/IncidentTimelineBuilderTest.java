package com.etiya.replaylab.service;

import com.etiya.replaylab.model.AdaptiveLokiSearchResult;
import com.etiya.replaylab.model.IntegrationModels.LokiLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentTimelineBuilderTest {

    private final IncidentTimelineBuilder builder =
            new IncidentTimelineBuilder(
                    new ObjectMapper()
            );

    @Test
    void buildsOrderedAndDeduplicatedTimeline() {
        LokiLogEntry first =
                new LokiLogEntry(
                        Instant.parse(
                                "2026-06-15T10:20:10Z"
                        ),
                        "{\"app\":\"bss-backend\"}",
                        """
                        "POST /customerorder/omintegration/complete HTTP/1.1"
                        401 via_upstream
                        """
                );

        LokiLogEntry second =
                new LokiLogEntry(
                        Instant.parse(
                                "2026-06-15T10:20:12Z"
                        ),
                        "{\"app\":\"bss-backend-batch\"}",
                        "ERROR RECURRING_PAYMENT_CALLBACK code=9999"
                );

        var firstPass =
                new AdaptiveLokiSearchResult(
                        List.of(),
                        List.of(first)
                );

        var secondPass =
                new AdaptiveLokiSearchResult(
                        List.of(),
                        List.of(first, second)
                );

        var result =
                builder.build(
                        firstPass,
                        secondPass
                );

        assertThat(result.eventCount())
                .isEqualTo(2);

        assertThat(result.events().get(0).application())
                .isEqualTo("bss-backend");

        assertThat(result.events().get(0).httpMethod())
                .isEqualTo("POST");

        assertThat(result.events().get(0).endpoint())
                .isEqualTo(
                        "/customerorder/omintegration/complete"
                );

        assertThat(result.events().get(0).httpStatus())
                .isEqualTo(401);

        assertThat(result.events().get(0).severity())
                .isEqualTo("WARN");

        assertThat(result.events().get(1).severity())
                .isEqualTo("ERROR");

        assertThat(result.applicationCounts())
                .containsEntry("bss-backend", 1)
                .containsEntry("bss-backend-batch", 1);
    }
}
