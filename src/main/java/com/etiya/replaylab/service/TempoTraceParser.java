package com.etiya.replaylab.service;

import com.etiya.replaylab.model.TempoNormalizedSpan;
import com.etiya.replaylab.model.TempoSpanEvent;
import com.etiya.replaylab.model.TempoSpanStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TempoTraceParser {

    private final ObjectMapper objectMapper;
    private final EvidenceSanitizer evidenceSanitizer;

    public TempoTraceParser(
            ObjectMapper objectMapper,
            EvidenceSanitizer evidenceSanitizer
    ) {
        this.objectMapper = objectMapper;
        this.evidenceSanitizer = evidenceSanitizer;
    }

    public List<TempoNormalizedSpan> parse(
            String traceId,
            String rawJson,
            int maxSpans
    ) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            List<TempoNormalizedSpan> spans = new ArrayList<>();

            extractSpans(root, traceId, spans);

            if (spans.size() > maxSpans) {
                spans = spans.subList(0, maxSpans);
            }

            markRootSpans(spans);

            return spans;

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse Tempo trace JSON.",
                    exception
            );
        }
    }

    private void extractSpans(
            JsonNode root,
            String traceId,
            List<TempoNormalizedSpan> spans
    ) {
        if (root.has("batches")) {
            extractFromBatches(root.get("batches"), traceId, spans);
        } else if (root.has("traces")) {
            extractFromTraces(root.get("traces"), traceId, spans);
        } else if (root.has("resourceSpans")) {
            extractFromResourceSpans(root.get("resourceSpans"), traceId, spans);
        } else if (root.isArray()) {
            for (JsonNode item : root) {
                if (item.has("spans")) {
                    extractSpansFromArray(item.get("spans"), traceId, "", spans);
                }
            }
        }
    }

    private void extractFromBatches(
            JsonNode batches,
            String traceId,
            List<TempoNormalizedSpan> spans
    ) {
        if (!batches.isArray()) {
            return;
        }

        for (JsonNode batch : batches) {
            String serviceName = extractServiceName(batch);

            if (batch.has("instrumentationLibrarySpans")) {
                JsonNode libSpans = batch.get("instrumentationLibrarySpans");
                for (JsonNode libSpan : libSpans) {
                    if (libSpan.has("spans")) {
                        extractSpansFromArray(
                                libSpan.get("spans"),
                                traceId,
                                serviceName,
                                spans
                        );
                    }
                }
            } else if (batch.has("scopeSpans")) {
                JsonNode scopeSpans = batch.get("scopeSpans");
                for (JsonNode scopeSpan : scopeSpans) {
                    if (scopeSpan.has("spans")) {
                        extractSpansFromArray(
                                scopeSpan.get("spans"),
                                traceId,
                                serviceName,
                                spans
                        );
                    }
                }
            }
        }
    }

    private void extractFromTraces(
            JsonNode traces,
            String traceId,
            List<TempoNormalizedSpan> spans
    ) {
        if (!traces.isArray()) {
            return;
        }

        for (JsonNode trace : traces) {
            if (trace.has("resourceSpans")) {
                extractFromResourceSpans(trace.get("resourceSpans"), traceId, spans);
            }
        }
    }

    private void extractFromResourceSpans(
            JsonNode resourceSpans,
            String traceId,
            List<TempoNormalizedSpan> spans
    ) {
        if (!resourceSpans.isArray()) {
            return;
        }

        for (JsonNode resourceSpan : resourceSpans) {
            String serviceName = extractServiceName(resourceSpan);

            if (resourceSpan.has("scopeSpans")) {
                JsonNode scopeSpans = resourceSpan.get("scopeSpans");
                for (JsonNode scopeSpan : scopeSpans) {
                    if (scopeSpan.has("spans")) {
                        extractSpansFromArray(
                                scopeSpan.get("spans"),
                                traceId,
                                serviceName,
                                spans
                        );
                    }
                }
            }
        }
    }

    private void extractSpansFromArray(
            JsonNode spansArray,
            String traceId,
            String defaultServiceName,
            List<TempoNormalizedSpan> spans
    ) {
        if (!spansArray.isArray()) {
            return;
        }

        for (JsonNode spanNode : spansArray) {
            TempoNormalizedSpan span = parseSpan(
                    spanNode,
                    traceId,
                    defaultServiceName
            );

            if (span != null) {
                spans.add(span);
            }
        }
    }

    private TempoNormalizedSpan parseSpan(
            JsonNode spanNode,
            String traceId,
            String defaultServiceName
    ) {
        String spanId = safeText(spanNode.get("spanId"));
        if (spanId == null) {
            return null;
        }

        String parentSpanId = safeText(spanNode.get("parentSpanId"));
        String serviceName = extractServiceNameFromSpan(spanNode, defaultServiceName);
        String operationName = safeText(spanNode.get("name"));
        String kind = safeText(spanNode.get("kind"));
        String startTime = safeText(spanNode.get("startTimeUnixNano"));
        String endTime = safeText(spanNode.get("endTimeUnixNano"));

        long durationMicros = calculateDuration(startTime, endTime);

        TempoSpanStatus status = extractStatus(spanNode);
        String statusMessage = safeText(spanNode.path("status").get("message"));

        Map<String, String> attributes = extractAttributes(spanNode.get("attributes"));
        List<TempoSpanEvent> events = extractEvents(spanNode.get("events"));

        boolean error = isErrorSpan(status, attributes, events);

        return new TempoNormalizedSpan(
                traceId,
                spanId,
                parentSpanId,
                serviceName,
                operationName,
                kind,
                startTime,
                endTime,
                durationMicros,
                status,
                statusMessage,
                attributes,
                events,
                error,
                false
        );
    }

    private String extractServiceName(JsonNode batch) {
        if (batch.has("resource")) {
            JsonNode resource = batch.get("resource");

            if (resource.has("attributes")) {
                JsonNode attributes = resource.get("attributes");
                String serviceName = findAttributeValue(attributes, "service.name");
                if (serviceName != null) {
                    return serviceName;
                }
            }

            String serviceName = safeText(resource.get("serviceName"));
            if (serviceName != null) {
                return serviceName;
            }
        }

        if (batch.has("process")) {
            String serviceName = safeText(batch.path("process").get("serviceName"));
            if (serviceName != null) {
                return serviceName;
            }
        }

        return "unknown-service";
    }

    private String extractServiceNameFromSpan(
            JsonNode spanNode,
            String defaultServiceName
    ) {
        if (spanNode.has("attributes")) {
            String serviceName = findAttributeValue(
                    spanNode.get("attributes"),
                    "service.name"
            );
            if (serviceName != null) {
                return serviceName;
            }
        }

        String serviceName = safeText(spanNode.get("serviceName"));
        if (serviceName != null) {
            return serviceName;
        }

        return defaultServiceName != null && !defaultServiceName.isBlank()
                ? defaultServiceName
                : "unknown-service";
    }

    private TempoSpanStatus extractStatus(JsonNode spanNode) {
        JsonNode status = spanNode.get("status");

        if (status != null) {
            String code = safeText(status.get("code"));
            if ("ERROR".equalsIgnoreCase(code) || "2".equals(code)) {
                return TempoSpanStatus.ERROR;
            }
            if ("OK".equalsIgnoreCase(code) || "1".equals(code)) {
                return TempoSpanStatus.OK;
            }
            if ("UNSET".equalsIgnoreCase(code) || "0".equals(code)) {
                return TempoSpanStatus.UNSET;
            }
        }

        String statusCode = safeText(spanNode.get("statusCode"));
        if ("ERROR".equalsIgnoreCase(statusCode)) {
            return TempoSpanStatus.ERROR;
        }

        return TempoSpanStatus.UNKNOWN;
    }

    private Map<String, String> extractAttributes(JsonNode attributes) {
        if (attributes == null || !attributes.isArray()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();

        for (JsonNode attr : attributes) {
            String key = safeText(attr.get("key"));
            if (key == null) {
                continue;
            }

            JsonNode value = attr.get("value");
            String stringValue = extractAttributeValue(value);

            if (stringValue != null) {
                String sanitized = evidenceSanitizer.sanitize(stringValue);
                result.put(key, sanitized);
            }
        }

        return result;
    }

    private String extractAttributeValue(JsonNode value) {
        if (value == null) {
            return null;
        }

        if (value.has("stringValue")) {
            return safeText(value.get("stringValue"));
        }
        if (value.has("intValue")) {
            return String.valueOf(value.get("intValue").asLong());
        }
        if (value.has("boolValue")) {
            return String.valueOf(value.get("boolValue").asBoolean());
        }
        if (value.has("doubleValue")) {
            return String.valueOf(value.get("doubleValue").asDouble());
        }

        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber()) {
            return String.valueOf(value.asLong());
        }
        if (value.isBoolean()) {
            return String.valueOf(value.asBoolean());
        }

        return null;
    }

    private List<TempoSpanEvent> extractEvents(JsonNode events) {
        if (events == null || !events.isArray()) {
            return List.of();
        }

        List<TempoSpanEvent> result = new ArrayList<>();

        for (JsonNode event : events) {
            String name = safeText(event.get("name"));
            String timestamp = safeText(event.get("timeUnixNano"));
            Map<String, String> attributes = extractAttributes(event.get("attributes"));

            result.add(new TempoSpanEvent(name, timestamp, attributes));
        }

        return result;
    }

    private boolean isErrorSpan(
            TempoSpanStatus status,
            Map<String, String> attributes,
            List<TempoSpanEvent> events
    ) {
        if (status == TempoSpanStatus.ERROR) {
            return true;
        }

        if (attributes.containsKey("error") && "true".equals(attributes.get("error"))) {
            return true;
        }

        String httpStatus = attributes.get("http.status_code");
        if (httpStatus != null) {
            try {
                int code = Integer.parseInt(httpStatus);
                if (code >= 500) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        String grpcStatus = attributes.get("rpc.grpc.status_code");
        if (grpcStatus != null && !"0".equals(grpcStatus)) {
            return true;
        }

        for (TempoSpanEvent event : events) {
            String eventName = event.name();
            if (eventName != null && eventName.toLowerCase().contains("exception")) {
                return true;
            }
        }

        return false;
    }

    private String findAttributeValue(JsonNode attributes, String key) {
        if (attributes == null || !attributes.isArray()) {
            return null;
        }

        for (JsonNode attr : attributes) {
            String attrKey = safeText(attr.get("key"));
            if (key.equals(attrKey)) {
                JsonNode value = attr.get("value");
                return extractAttributeValue(value);
            }
        }

        return null;
    }

    private long calculateDuration(String startTime, String endTime) {
        if (startTime == null || endTime == null) {
            return 0;
        }

        try {
            long start = Long.parseLong(startTime);
            long end = Long.parseLong(endTime);
            return (end - start) / 1000;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void markRootSpans(List<TempoNormalizedSpan> spans) {
        Set<String> allSpanIds = new HashSet<>();
        Set<String> childSpanIds = new HashSet<>();

        for (TempoNormalizedSpan span : spans) {
            allSpanIds.add(span.spanId());
            if (span.parentSpanId() != null && !span.parentSpanId().isBlank()) {
                childSpanIds.add(span.spanId());
            }
        }

        for (int i = 0; i < spans.size(); i++) {
            TempoNormalizedSpan span = spans.get(i);

            boolean isRoot = (span.parentSpanId() == null
                    || span.parentSpanId().isBlank()
                    || !allSpanIds.contains(span.parentSpanId()));

            if (isRoot != span.rootSpan()) {
                spans.set(i, new TempoNormalizedSpan(
                        span.traceId(),
                        span.spanId(),
                        span.parentSpanId(),
                        span.serviceName(),
                        span.operationName(),
                        span.kind(),
                        span.startTime(),
                        span.endTime(),
                        span.durationMicros(),
                        span.status(),
                        span.statusMessage(),
                        span.attributes(),
                        span.events(),
                        span.error(),
                        isRoot
                ));
            }
        }
    }

    private String safeText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }

        return node.asText();
    }
}
