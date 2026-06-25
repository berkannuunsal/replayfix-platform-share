package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.model.TraceIdCandidate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TraceIdCandidateExtractor {

    private static final Pattern TRACE_ID_PATTERN_32 = Pattern.compile(
            "(?:trace[_-]?id[:=]\\s*['\"]?)([a-fA-F0-9]{32})(?:['\"]?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRACE_ID_PATTERN_16 = Pattern.compile(
            "(?:trace[_-]?id[:=]\\s*['\"]?)([a-fA-F0-9]{16})(?:['\"]?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "traceparent[:=]\\s*['\"]?00-([a-fA-F0-9]{32})-[a-fA-F0-9]{16}-[a-fA-F0-9]{2}['\"]?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern B3_TRACE_ID_PATTERN = Pattern.compile(
            "X-B3-TraceId[:=]\\s*['\"]?([a-fA-F0-9]{16,32})['\"]?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HEX_32_PATTERN = Pattern.compile(
            "\\b([a-fA-F0-9]{32})\\b"
    );

    private static final Pattern HEX_16_PATTERN = Pattern.compile(
            "\\b([a-fA-F0-9]{16})\\b"
    );

    public List<TraceIdCandidate> extract(
            UUID caseId,
            List<EvidenceEntity> evidence
    ) {
        Map<String, TraceIdCandidate> candidateMap = new LinkedHashMap<>();

        for (EvidenceEntity entity : evidence) {
            String content = entity.getContentText();
            if (content == null || content.isBlank()) {
                continue;
            }

            String source = determineSource(entity);
            int baseConfidence = calculateBaseConfidence(entity);

            extractFromContent(content, source, baseConfidence, candidateMap);
        }

        return candidateMap.values().stream()
                .sorted(Comparator.comparingInt(TraceIdCandidate::confidence).reversed())
                .toList();
    }

    private void extractFromContent(
            String content,
            String source,
            int baseConfidence,
            Map<String, TraceIdCandidate> candidateMap
    ) {
        extractWithPattern(
                content,
                TRACEPARENT_PATTERN,
                source,
                baseConfidence + 95,
                "traceparent header format",
                candidateMap
        );

        extractWithPattern(
                content,
                B3_TRACE_ID_PATTERN,
                source,
                baseConfidence + 90,
                "X-B3-TraceId header",
                candidateMap
        );

        extractWithPattern(
                content,
                TRACE_ID_PATTERN_32,
                source,
                baseConfidence + 80,
                "trace_id field (32 hex)",
                candidateMap
        );

        extractWithPattern(
                content,
                TRACE_ID_PATTERN_16,
                source,
                baseConfidence + 75,
                "trace_id field (16 hex)",
                candidateMap
        );

        extractWithPattern(
                content,
                HEX_32_PATTERN,
                source,
                baseConfidence + 30,
                "standalone 32 hex",
                candidateMap
        );

        extractWithPattern(
                content,
                HEX_16_PATTERN,
                source,
                baseConfidence + 20,
                "standalone 16 hex",
                candidateMap
        );
    }

    private void extractWithPattern(
            String content,
            Pattern pattern,
            String source,
            int confidence,
            String reason,
            Map<String, TraceIdCandidate> candidateMap
    ) {
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String traceId = matcher.group(1);
            String normalized = normalizeTraceId(traceId);

            if (!isValidTraceId(normalized)) {
                continue;
            }

            TraceIdCandidate existing = candidateMap.get(normalized);

            if (existing == null || existing.confidence() < confidence) {
                List<String> reasons = new ArrayList<>();
                reasons.add(reason);
                reasons.add("source: " + source);

                candidateMap.put(
                        normalized,
                        new TraceIdCandidate(
                                traceId,
                                normalized,
                                source,
                                confidence,
                                reasons
                        )
                );
            }
        }
    }

    private String normalizeTraceId(String traceId) {
        if (traceId == null) {
            return null;
        }

        return traceId.toLowerCase()
                .replaceAll("[\\s\\-_]", "");
    }

    private boolean isValidTraceId(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }

        int length = normalized.length();
        return (length == 16 || length == 32) && normalized.matches("[a-f0-9]+");
    }

    private String determineSource(EvidenceEntity entity) {
        String source = entity.getSource();

        if (source != null) {
            if (source.contains("jira")) {
                return "JIRA_ISSUE";
            }
            if (source.contains("loki")) {
                return "LOKI_LOG";
            }
            if (source.contains("ai") || source.contains("root-cause")) {
                return "AI_ROOT_CAUSE";
            }
        }

        return entity.getEvidenceType() != null
                ? entity.getEvidenceType().name()
                : "UNKNOWN";
    }

    private int calculateBaseConfidence(EvidenceEntity entity) {
        String source = entity.getSource();

        if (source == null) {
            return 0;
        }

        if (source.contains("loki-structured") || source.contains("loki-query-plan")) {
            return 100;
        }

        if (source.contains("jira-description") || source.contains("jira-comment")) {
            return 50;
        }

        if (source.contains("ai") || source.contains("root-cause")) {
            return 40;
        }

        return 10;
    }
}
