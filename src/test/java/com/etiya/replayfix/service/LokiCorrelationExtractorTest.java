package com.etiya.replayfix.service;

import com.etiya.replayfix.model.IntegrationModels.LokiLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LokiCorrelationExtractorTest {

    private final LokiCorrelationExtractor extractor =
            new LokiCorrelationExtractor();

    @Test
    void extractsNamedCorrelationValues() {
        var logs = List.of(
                new LokiLogEntry(
                        Instant.parse(
                                "2026-06-15T10:20:00Z"
                        ),
                        "{\"app\":\"bss-backend\"}",
                        """
                        traceId=abc123-trace
                        orderId=987654321
                        correlationId=correlation-55
                        processInstanceId=process-1234
                        businessKey=BAR-5678
                        requestId=request-9876
                        """
                )
        );

        var result = extractor.extract(logs);

        assertThat(result.traceIds())
                .contains("abc123-trace");

        assertThat(result.orderIds())
                .contains("987654321");

        assertThat(result.correlationIds())
                .contains("correlation-55");

        assertThat(result.processInstanceIds())
                .contains("process-1234");

        assertThat(result.businessKeys())
                .contains("BAR-5678");

        assertThat(result.requestIds())
                .contains("request-9876");
    }

    @Test
    void extractsTraceIdFromTraceparent() {
        var logs = List.of(
                new LokiLogEntry(
                        Instant.now(),
                        "{\"app\":\"bss-backend\"}",
                        "traceparent="
                                + "00-"
                                + "4bf92f3577b34da6a3ce929d0e0e4736"
                                + "-"
                                + "00f067aa0ba902b7"
                                + "-01"
                )
        );

        var result = extractor.extract(logs);

        assertThat(result.traceIds())
                .contains(
                        "4bf92f3577b34da6a3ce929d0e0e4736"
                );
    }

    @Test
    void extractsUuidFromIstioAccessLog() {
        var logs = List.of(
                new LokiLogEntry(
                        Instant.now(),
                        "{\"app\":\"bss-backend\"}",
                        """
                        "POST /customerorder/complete HTTP/1.1"
                        401 via_upstream
                        "34d99b04-2d57-47b7-bf63-1f398a849a13"
                        inbound|9080
                        """
                )
        );

        var result = extractor.extract(logs);

        assertThat(result.requestIds())
                .contains(
                        "34d99b04-2d57-47b7-bf63-1f398a849a13"
                );
    }
}
