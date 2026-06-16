package com.etiya.replayfix.service;

import com.etiya.replayfix.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TempoTraceTimelineBuilder {

    public TempoTraceSummary build(
            String traceId,
            List<TempoNormalizedSpan> spans
    ) {
        if (spans.isEmpty()) {
            return createEmptySummary(traceId);
        }

        List<String> warnings = new ArrayList<>();

        TempoNormalizedSpan rootSpan = findRootSpan(spans);

        String rootService = rootSpan != null
                ? rootSpan.serviceName()
                : "unknown";

        String rootOperation = rootSpan != null
                ? rootSpan.operationName()
                : "unknown";

        String startTime = rootSpan != null
                ? rootSpan.startTime()
                : spans.get(0).startTime();

        long totalDuration = calculateTotalDuration(spans, rootSpan);

        Set<String> services = spans.stream()
                .map(TempoNormalizedSpan::serviceName)
                .collect(Collectors.toSet());

        List<TempoNormalizedSpan> errorSpans = spans.stream()
                .filter(TempoNormalizedSpan::error)
                .toList();

        List<TempoServiceHop> timeline = buildTimeline(spans);

        List<String> probableFailureServices = identifyProbableFailureServices(
                spans,
                errorSpans
        );

        List<String> criticalPath = identifyCriticalPath(spans, rootSpan, warnings);

        return new TempoTraceSummary(
                traceId,
                rootService,
                rootOperation,
                startTime,
                totalDuration,
                spans.size(),
                services.size(),
                errorSpans.size(),
                new ArrayList<>(services),
                timeline,
                errorSpans,
                criticalPath,
                probableFailureServices,
                warnings
        );
    }

    private TempoTraceSummary createEmptySummary(String traceId) {
        return new TempoTraceSummary(
                traceId,
                "unknown",
                "unknown",
                null,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("No spans found in trace")
        );
    }

    private TempoNormalizedSpan findRootSpan(List<TempoNormalizedSpan> spans) {
        return spans.stream()
                .filter(TempoNormalizedSpan::rootSpan)
                .findFirst()
                .orElse(spans.get(0));
    }

    private long calculateTotalDuration(
            List<TempoNormalizedSpan> spans,
            TempoNormalizedSpan rootSpan
    ) {
        if (rootSpan != null) {
            return rootSpan.durationMicros();
        }

        return spans.stream()
                .mapToLong(TempoNormalizedSpan::durationMicros)
                .max()
                .orElse(0);
    }

    private List<TempoServiceHop> buildTimeline(List<TempoNormalizedSpan> spans) {
        List<TempoNormalizedSpan> sortedSpans = spans.stream()
                .sorted(Comparator.comparing(TempoNormalizedSpan::startTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<TempoServiceHop> timeline = new ArrayList<>();
        int order = 0;

        for (TempoNormalizedSpan span : sortedSpans) {
            List<String> signals = new ArrayList<>();

            if (span.error()) {
                signals.add("ERROR");
            }

            if (span.statusMessage() != null && !span.statusMessage().isBlank()) {
                signals.add("status: " + span.statusMessage());
            }

            String httpStatus = span.attributes().get("http.status_code");
            if (httpStatus != null) {
                signals.add("http: " + httpStatus);
            }

            timeline.add(new TempoServiceHop(
                    order++,
                    span.serviceName(),
                    span.operationName(),
                    span.spanId(),
                    span.parentSpanId(),
                    span.durationMicros(),
                    span.status().name(),
                    span.error(),
                    signals
            ));
        }

        return timeline;
    }

    private List<String> identifyProbableFailureServices(
            List<TempoNormalizedSpan> spans,
            List<TempoNormalizedSpan> errorSpans
    ) {
        if (errorSpans.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> serviceErrorCount = new HashMap<>();

        for (TempoNormalizedSpan errorSpan : errorSpans) {
            String service = errorSpan.serviceName();
            serviceErrorCount.put(service, serviceErrorCount.getOrDefault(service, 0) + 1);
        }

        TempoNormalizedSpan firstError = errorSpans.stream()
                .min(Comparator.comparing(
                        TempoNormalizedSpan::startTime,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .orElse(null);

        if (firstError != null) {
            String firstErrorService = firstError.serviceName();

            boolean hasException = firstError.events().stream()
                    .anyMatch(e -> e.name() != null
                            && e.name().toLowerCase().contains("exception"));

            if (hasException) {
                return List.of(firstErrorService);
            }

            boolean isDownstreamError = errorSpans.stream()
                    .anyMatch(e -> e.parentSpanId() != null
                            && e.parentSpanId().equals(firstError.spanId()));

            if (!isDownstreamError) {
                return List.of(firstErrorService);
            }
        }

        return serviceErrorCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> identifyCriticalPath(
            List<TempoNormalizedSpan> spans,
            TempoNormalizedSpan rootSpan,
            List<String> warnings
    ) {
        if (rootSpan == null) {
            return List.of();
        }

        List<String> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        findLongestPath(rootSpan.spanId(), spans, path, visited, warnings);

        return path;
    }

    private long findLongestPath(
            String spanId,
            List<TempoNormalizedSpan> spans,
            List<String> path,
            Set<String> visited,
            List<String> warnings
    ) {
        if (visited.contains(spanId)) {
            warnings.add("Cycle detected in span tree at spanId: " + spanId);
            return 0;
        }

        visited.add(spanId);

        TempoNormalizedSpan currentSpan = spans.stream()
                .filter(s -> spanId.equals(s.spanId()))
                .findFirst()
                .orElse(null);

        if (currentSpan == null) {
            return 0;
        }

        List<TempoNormalizedSpan> children = spans.stream()
                .filter(s -> spanId.equals(s.parentSpanId()))
                .toList();

        if (children.isEmpty()) {
            path.add(spanId);
            return currentSpan.durationMicros();
        }

        long maxChildDuration = 0;
        String maxChildSpanId = null;

        for (TempoNormalizedSpan child : children) {
            List<String> childPath = new ArrayList<>();
            long childDuration = findLongestPath(
                    child.spanId(),
                    spans,
                    childPath,
                    new HashSet<>(visited),
                    warnings
            );

            if (childDuration > maxChildDuration) {
                maxChildDuration = childDuration;
                maxChildSpanId = child.spanId();
            }
        }

        path.add(spanId);

        if (maxChildSpanId != null) {
            findLongestPath(maxChildSpanId, spans, path, visited, warnings);
        }

        return currentSpan.durationMicros() + maxChildDuration;
    }
}
