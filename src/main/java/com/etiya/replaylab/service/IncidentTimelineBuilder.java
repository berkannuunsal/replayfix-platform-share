package com.etiya.replaylab.service;

import com.etiya.replaylab.model.AdaptiveLokiSearchResult;
import com.etiya.replaylab.model.IncidentTimeline;
import com.etiya.replaylab.model.IncidentTimelineEvent;
import com.etiya.replaylab.model.IntegrationModels.LokiLogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IncidentTimelineBuilder {

    private static final int MAX_MESSAGE_LENGTH = 2_000;

    private static final Pattern HTTP_ACCESS_PATTERN =
            Pattern.compile(
                    "(?i)\"?"
                            + "(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)"
                            + "\\s+([^\\s\"]+)"
                            + "\\s+HTTP/\\d(?:\\.\\d)?"
                            + "\"?\\s+(\\d{3})"
            );

    private static final Pattern HTTP_STATUS_PATTERN =
            Pattern.compile(
                    "(?i)\\bHTTP\\s*[:=]?\\s*(\\d{3})\\b"
            );

    private static final Pattern SEVERITY_PATTERN =
            Pattern.compile(
                    "(?i)\\b(FATAL|ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\b"
            );

    private final ObjectMapper objectMapper;

    public IncidentTimelineBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IncidentTimeline build(
            AdaptiveLokiSearchResult firstPass,
            AdaptiveLokiSearchResult secondPass
    ) {
        Map<String, IncidentTimelineEvent> uniqueEvents =
                new LinkedHashMap<>();

        addLogs(
                uniqueEvents,
                firstPass == null
                        ? List.of()
                        : firstPass.logs(),
                "FIRST_PASS"
        );

        addLogs(
                uniqueEvents,
                secondPass == null
                        ? List.of()
                        : secondPass.logs(),
                "SECOND_PASS"
        );

        List<IncidentTimelineEvent> events =
                uniqueEvents.values()
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        IncidentTimelineEvent::timestamp
                                )
                        )
                        .toList();

        Map<String, Integer> applicationCounts =
                countByApplication(events);

        Map<String, Integer> severityCounts =
                countBySeverity(events);

        Map<String, Integer> httpStatusCounts =
                countByHttpStatus(events);

        Instant startedAt =
                events.isEmpty()
                        ? null
                        : events.get(0).timestamp();

        Instant endedAt =
                events.isEmpty()
                        ? null
                        : events.get(events.size() - 1)
                                .timestamp();

        return new IncidentTimeline(
                startedAt,
                endedAt,
                events.size(),
                applicationCounts,
                severityCounts,
                httpStatusCounts,
                events
        );
    }

    private void addLogs(
            Map<String, IncidentTimelineEvent> uniqueEvents,
            List<LokiLogEntry> logs,
            String searchPass
    ) {
        if (logs == null) {
            return;
        }

        for (LokiLogEntry log : logs) {
            if (log == null || log.timestamp() == null) {
                continue;
            }

            String line = safe(log.line());
            String application =
                    extractApplication(log.labels());

            HttpDetails httpDetails =
                    extractHttpDetails(line);

            String severity =
                    extractSeverity(
                            line,
                            httpDetails.httpStatus()
                    );

            String normalizedMessage =
                    normalizeMessage(line);

            IncidentTimelineEvent event =
                    new IncidentTimelineEvent(
                            log.timestamp(),
                            application,
                            searchPass,
                            severity,
                            httpDetails.httpMethod(),
                            httpDetails.endpoint(),
                            httpDetails.httpStatus(),
                            normalizedMessage
                    );

            String key =
                    log.timestamp()
                            + "|"
                            + application
                            + "|"
                            + normalizedMessage;

            uniqueEvents.putIfAbsent(key, event);
        }
    }

    private String extractApplication(String labels) {
        if (labels == null || labels.isBlank()) {
            return "unknown";
        }

        try {
            JsonNode root =
                    objectMapper.readTree(labels);

            for (String field : List.of(
                    "app",
                    "service_name",
                    "service",
                    "component",
                    "container",
                    "job"
            )) {
                String value =
                        root.path(field).asText("");

                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
        }

        Matcher matcher =
                Pattern.compile(
                        "(?i)\"app\"\\s*:\\s*\"([^\"]+)\""
                ).matcher(labels);

        return matcher.find()
                ? matcher.group(1)
                : "unknown";
    }

    private HttpDetails extractHttpDetails(String line) {
        Matcher accessMatcher =
                HTTP_ACCESS_PATTERN.matcher(line);

        if (accessMatcher.find()) {
            return new HttpDetails(
                    accessMatcher.group(1)
                            .toUpperCase(Locale.ROOT),
                    accessMatcher.group(2),
                    parseStatus(accessMatcher.group(3))
            );
        }

        Matcher statusMatcher =
                HTTP_STATUS_PATTERN.matcher(line);

        Integer status =
                statusMatcher.find()
                        ? parseStatus(statusMatcher.group(1))
                        : null;

        return new HttpDetails(
                null,
                null,
                status
        );
    }

    private String extractSeverity(
            String line,
            Integer httpStatus
    ) {
        Matcher matcher =
                SEVERITY_PATTERN.matcher(line);

        if (matcher.find()) {
            String value =
                    matcher.group(1)
                            .toUpperCase(Locale.ROOT);

            return "WARNING".equals(value)
                    ? "WARN"
                    : value;
        }

        if (httpStatus != null) {
            if (httpStatus >= 500) {
                return "ERROR";
            }

            if (httpStatus >= 400) {
                return "WARN";
            }
        }

        return "INFO";
    }

    private Integer parseStatus(String value) {
        try {
            return Integer.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeMessage(String line) {
        String normalized =
                safe(line)
                        .replaceAll("[\\r\\n\\t]+", " ")
                        .replaceAll("\\s{2,}", " ")
                        .trim();

        if (normalized.length() <= MAX_MESSAGE_LENGTH) {
            return normalized;
        }

        return normalized.substring(
                0,
                MAX_MESSAGE_LENGTH
        );
    }

    private Map<String, Integer> countByApplication(
            List<IncidentTimelineEvent> events
    ) {
        Map<String, Integer> counts =
                new TreeMap<>();

        for (IncidentTimelineEvent event : events) {
            counts.merge(
                    event.application(),
                    1,
                    Integer::sum
            );
        }

        return counts;
    }

    private Map<String, Integer> countBySeverity(
            List<IncidentTimelineEvent> events
    ) {
        Map<String, Integer> counts =
                new TreeMap<>();

        for (IncidentTimelineEvent event : events) {
            counts.merge(
                    event.severity(),
                    1,
                    Integer::sum
            );
        }

        return counts;
    }

    private Map<String, Integer> countByHttpStatus(
            List<IncidentTimelineEvent> events
    ) {
        Map<String, Integer> counts =
                new TreeMap<>();

        for (IncidentTimelineEvent event : events) {
            if (event.httpStatus() == null) {
                continue;
            }

            counts.merge(
                    event.httpStatus().toString(),
                    1,
                    Integer::sum
            );
        }

        return counts;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record HttpDetails(
            String httpMethod,
            String endpoint,
            Integer httpStatus
    ) {
    }
}
