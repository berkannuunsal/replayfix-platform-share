package com.etiya.replaylab.service;

import com.etiya.replaylab.model.CorrelationSignals;
import com.etiya.replaylab.model.IntegrationModels.LokiLogEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class LokiCorrelationExtractor {

    private static final int MAX_VALUES_PER_TYPE = 25;

    private static final Pattern TRACE_ID_PATTERN =
            namedPattern(
                    "traceId",
                    "trace_id",
                    "trace-id",
                    "x-b3-traceid"
            );

    private static final Pattern ORDER_ID_PATTERN =
            namedPattern(
                    "orderId",
                    "order_id",
                    "customerOrderId",
                    "customer_order_id",
                    "businessInteractionId"
            );

    private static final Pattern CORRELATION_ID_PATTERN =
            namedPattern(
                    "correlationId",
                    "correlation_id",
                    "correlation-id",
                    "x-correlation-id"
            );

    private static final Pattern PROCESS_INSTANCE_ID_PATTERN =
            namedPattern(
                    "processInstanceId",
                    "process_instance_id",
                    "process-instance-id"
            );

    private static final Pattern BUSINESS_KEY_PATTERN =
            namedPattern(
                    "businessKey",
                    "business_key",
                    "business-key"
            );

    private static final Pattern REQUEST_ID_PATTERN =
            namedPattern(
                    "requestId",
                    "request_id",
                    "request-id",
                    "x-request-id"
            );

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile(
                    "(?i)\\b00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}\\b"
            );

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "(?i)\\b[0-9a-f]{8}-"
                            + "[0-9a-f]{4}-"
                            + "[1-5][0-9a-f]{3}-"
                            + "[89ab][0-9a-f]{3}-"
                            + "[0-9a-f]{12}\\b"
            );

    public CorrelationSignals extract(List<LokiLogEntry> logs) {
        Set<String> traceIds = new LinkedHashSet<>();
        Set<String> orderIds = new LinkedHashSet<>();
        Set<String> correlationIds = new LinkedHashSet<>();
        Set<String> processInstanceIds = new LinkedHashSet<>();
        Set<String> businessKeys = new LinkedHashSet<>();
        Set<String> requestIds = new LinkedHashSet<>();

        for (LokiLogEntry log : logs) {
            String searchableText =
                    safe(log.labels())
                            + "\n"
                            + safe(log.line());

            extractValues(
                    searchableText,
                    TRACE_ID_PATTERN,
                    traceIds
            );

            extractValues(
                    searchableText,
                    ORDER_ID_PATTERN,
                    orderIds
            );

            extractValues(
                    searchableText,
                    CORRELATION_ID_PATTERN,
                    correlationIds
            );

            extractValues(
                    searchableText,
                    PROCESS_INSTANCE_ID_PATTERN,
                    processInstanceIds
            );

            extractValues(
                    searchableText,
                    BUSINESS_KEY_PATTERN,
                    businessKeys
            );

            extractValues(
                    searchableText,
                    REQUEST_ID_PATTERN,
                    requestIds
            );

            extractTraceparent(
                    searchableText,
                    traceIds
            );

            extractIstioRequestIds(
                    searchableText,
                    requestIds
            );
        }

        return new CorrelationSignals(
                new ArrayList<>(traceIds),
                new ArrayList<>(orderIds),
                new ArrayList<>(correlationIds),
                new ArrayList<>(processInstanceIds),
                new ArrayList<>(businessKeys),
                new ArrayList<>(requestIds)
        );
    }

    private static Pattern namedPattern(String... names) {
        String nameExpression =
                Arrays.stream(names)
                        .map(Pattern::quote)
                        .collect(Collectors.joining("|"));

        return Pattern.compile(
                "(?i)"
                        + "(?:\"?(?:"
                        + nameExpression
                        + ")\"?)"
                        + "\\s*(?:[:=]|\\s)\\s*"
                        + "\"?"
                        + "([A-Za-z0-9][A-Za-z0-9._:/-]{2,127})"
        );
    }

    private void extractValues(
            String text,
            Pattern pattern,
            Set<String> target
    ) {
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()
                && target.size() < MAX_VALUES_PER_TYPE) {

            String value = normalize(
                    matcher.group(1)
            );

            if (isUseful(value)) {
                target.add(value);
            }
        }
    }

    private void extractTraceparent(
            String text,
            Set<String> traceIds
    ) {
        Matcher matcher =
                TRACEPARENT_PATTERN.matcher(text);

        while (matcher.find()
                && traceIds.size() < MAX_VALUES_PER_TYPE) {

            traceIds.add(
                    matcher.group(1).toLowerCase(Locale.ROOT)
            );
        }
    }

    /**
     * Istio/Envoy access loglarında x-request-id her zaman
     * alan adıyla yazılmayabiliyor. Log satırı proxy loguna
     * benziyorsa UUID değerlerini requestId adayı kabul ederiz.
     */
    private void extractIstioRequestIds(
            String text,
            Set<String> requestIds
    ) {
        String lower =
                text.toLowerCase(Locale.ROOT);

        boolean looksLikeIstio =
                lower.contains("via_upstream")
                        || lower.contains("via_upstream_reset")
                        || lower.contains("inbound|")
                        || lower.contains("outbound|")
                        || lower.contains("passthroughcluster");

        if (!looksLikeIstio) {
            return;
        }

        Matcher matcher =
                UUID_PATTERN.matcher(text);

        while (matcher.find()
                && requestIds.size() < MAX_VALUES_PER_TYPE) {

            requestIds.add(
                    matcher.group()
                            .toLowerCase(Locale.ROOT)
            );
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim();

        while (!normalized.isEmpty()) {
            char last =
                    normalized.charAt(
                            normalized.length() - 1
                    );

            if ("\"',;)}]".indexOf(last) < 0) {
                break;
            }

            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }

        return normalized;
    }

    private boolean isUseful(String value) {
        if (value == null || value.length() < 4) {
            return false;
        }

        return !"null".equalsIgnoreCase(value)
                && !"undefined".equalsIgnoreCase(value)
                && !"unknown".equalsIgnoreCase(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
