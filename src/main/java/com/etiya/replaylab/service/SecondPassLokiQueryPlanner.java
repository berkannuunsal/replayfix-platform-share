package com.etiya.replaylab.service;

import com.etiya.replaylab.model.CorrelationSignals;
import com.etiya.replaylab.model.LokiQueryCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SecondPassLokiQueryPlanner {

    private static final int MAX_QUERY_COUNT = 25;

    private static final List<String> DEFAULT_APPLICATIONS =
            List.of(
                    "bss-backend",
                    "bss-backend-batch",
                    "ntf",
                    "loyalty"
            );

    public List<LokiQueryCandidate> plan(
            CorrelationSignals signals,
            List<String> applications
    ) {
        String selector =
                buildSelector(applications);

        Map<String, LokiQueryCandidate> queries =
                new LinkedHashMap<>();

        addValues(
                queries,
                selector,
                signals.traceIds(),
                100,
                "Trace ID correlation"
        );

        addValues(
                queries,
                selector,
                signals.correlationIds(),
                95,
                "Correlation ID correlation"
        );

        addValues(
                queries,
                selector,
                signals.orderIds(),
                90,
                "Order ID correlation"
        );

        addValues(
                queries,
                selector,
                signals.processInstanceIds(),
                85,
                "Camunda process instance correlation"
        );

        addValues(
                queries,
                selector,
                signals.businessKeys(),
                80,
                "Business key correlation"
        );

        addValues(
                queries,
                selector,
                signals.requestIds(),
                75,
                "Request ID correlation"
        );

        return queries.values()
                .stream()
                .sorted(
                        Comparator.comparingInt(
                                LokiQueryCandidate::priority
                        ).reversed()
                )
                .limit(MAX_QUERY_COUNT)
                .toList();
    }

    private void addValues(
            Map<String, LokiQueryCandidate> queries,
            String selector,
            List<String> values,
            int priority,
            String reason
    ) {
        for (String value : values) {
            String logQl =
                    selector
                            + " |= \""
                            + escape(value)
                            + "\"";

            queries.putIfAbsent(
                    logQl,
                    new LokiQueryCandidate(
                            priority,
                            reason + ": " + value,
                            logQl
                    )
            );
        }
    }

    private String buildSelector(
            List<String> applications
    ) {
        List<String> safeApplications =
                applications == null
                        || applications.isEmpty()
                        ? DEFAULT_APPLICATIONS
                        : applications.stream()
                                .map(this::sanitizeApplication)
                                .filter(value -> !value.isBlank())
                                .toList();

        if (safeApplications.isEmpty()) {
            safeApplications = DEFAULT_APPLICATIONS;
        }

        return "{app=~\"("
                + String.join("|", safeApplications)
                + ")\"}";
    }

    private String sanitizeApplication(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll(
                "[^A-Za-z0-9._-]",
                ""
        );
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
