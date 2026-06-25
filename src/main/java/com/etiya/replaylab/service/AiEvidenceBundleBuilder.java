package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.model.AiEvidenceBundle;
import com.etiya.replaylab.model.CorrelationSignals;
import com.etiya.replaylab.model.DeterministicRootCauseReport;
import com.etiya.replaylab.model.IncidentTimeline;
import com.etiya.replaylab.model.IncidentTimelineEvent;
import com.etiya.replaylab.model.TempoEnrichmentResult;
import com.etiya.replaylab.model.IntegrationModels.JiraIssue;
import com.etiya.replaylab.model.IntegrationModels.KnowledgeResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AiEvidenceBundleBuilder {

    private static final int MAX_DESCRIPTION_LENGTH = 8_000;
    private static final int MAX_TIMELINE_EVENTS = 25;
    private static final int MAX_CORRELATION_VALUES_PER_TYPE = 10;
    private static final int MAX_KNOWLEDGE_RESULTS = 5;
    private static final int MAX_KNOWLEDGE_CONTENT = 1_500;
    private static final int MAX_SOURCE_EXCERPTS = 8;
    private static final int MAX_SOURCE_CONTENT = 4_000;

    private final EvidenceSanitizer sanitizer;
    private final ReplayLabProperties properties;

    public AiEvidenceBundleBuilder(
            EvidenceSanitizer sanitizer,
            ReplayLabProperties properties
    ) {
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    public AiEvidenceBundle build(
            JiraIssue jiraIssue,
            String plainDescription,
            DeterministicRootCauseReport deterministicReport,
            CorrelationSignals correlations,
            IncidentTimeline timeline,
            TempoEnrichmentResult tempoEnrichment,
            List<KnowledgeResult> knowledgeResults,
            List<AiEvidenceBundle.SourceExcerpt> sourceExcerpts
    ) {
        return new AiEvidenceBundle(
                jiraIssue.key(),
                clean(jiraIssue.summary(), 1_000),
                clean(
                        plainDescription,
                        MAX_DESCRIPTION_LENGTH
                ),
                deterministicReport,
                trimCorrelations(correlations),
                selectTimelineEvents(timeline),
                summarizeTempo(tempoEnrichment),
                summarizeKnowledge(knowledgeResults),
                properties.getPolicy().isAllowAiSourceCode()
                        ? sanitizeSourceExcerpts(sourceExcerpts)
                        : List.of(),
                List.of(
                        "Use only evidence contained in this bundle.",
                        "Do not invent source files, line numbers, services or configuration values.",
                        "Treat the probable cause as a hypothesis until verified by a regression test.",
                        "Clearly state when evidence is insufficient or contradictory.",
                        "Do not recommend automatic merge or production deployment.",
                        "Human review is mandatory before code or configuration changes."
                )
        );
    }

    private CorrelationSignals trimCorrelations(
            CorrelationSignals signals
    ) {
        if (signals == null) {
            return new CorrelationSignals(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        return new CorrelationSignals(
                limit(signals.traceIds()),
                limit(signals.orderIds()),
                limit(signals.correlationIds()),
                limit(signals.processInstanceIds()),
                limit(signals.businessKeys()),
                limit(signals.requestIds())
        );
    }

    private List<String> limit(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value ->
                        value != null && !value.isBlank()
                )
                .distinct()
                .limit(MAX_CORRELATION_VALUES_PER_TYPE)
                .toList();
    }

    private List<IncidentTimelineEvent> selectTimelineEvents(
            IncidentTimeline timeline
    ) {
        if (timeline == null
                || timeline.events() == null) {
            return List.of();
        }

        List<IncidentTimelineEvent> selected =
                timeline.events()
                        .stream()
                        .sorted(
                                Comparator
                                        .comparingInt(
                                                this::timelineScore
                                        )
                                        .reversed()
                                        .thenComparing(
                                                IncidentTimelineEvent::timestamp,
                                                Comparator.nullsLast(
                                                        Comparator.naturalOrder()
                                                )
                                        )
                        )
                        .limit(MAX_TIMELINE_EVENTS)
                        .map(this::sanitizeTimelineEvent)
                        .sorted(
                                Comparator.comparing(
                                        IncidentTimelineEvent::timestamp,
                                        Comparator.nullsLast(
                                                Comparator.naturalOrder()
                                        )
                                )
                        )
                        .toList();

        return new ArrayList<>(selected);
    }

    private int timelineScore(
            IncidentTimelineEvent event
    ) {
        int score = 0;

        if ("ERROR".equalsIgnoreCase(event.severity())) {
            score += 100;
        } else if ("WARN".equalsIgnoreCase(event.severity())) {
            score += 80;
        }

        if (event.httpStatus() != null) {
            if (event.httpStatus() >= 500) {
                score += 90;
            } else if (event.httpStatus() >= 400) {
                score += 70;
            }
        }

        if (event.endpoint() != null
                && !event.endpoint().isBlank()) {
            score += 20;
        }

        if ("SECOND_PASS".equals(event.searchPass())) {
            score += 10;
        }

        return score;
    }

    private IncidentTimelineEvent sanitizeTimelineEvent(
            IncidentTimelineEvent event
    ) {
        return new IncidentTimelineEvent(
                event.timestamp(),
                clean(event.application(), 200),
                clean(event.searchPass(), 100),
                clean(event.severity(), 50),
                clean(event.httpMethod(), 20),
                clean(event.endpoint(), 1_000),
                event.httpStatus(),
                clean(event.message(), 2_000)
        );
    }

    private AiEvidenceBundle.TempoSummary summarizeTempo(
            TempoEnrichmentResult tempo
    ) {
        if (tempo == null || tempo.traces() == null) {
            return new AiEvidenceBundle.TempoSummary(
                    0,
                    0,
                    List.of(),
                    List.of()
            );
        }

        List<String> foundTraceIds =
                tempo.traces()
                        .stream()
                        .filter(trace -> trace != null && trace.found())
                        .map(trace -> trace.traceId())
                        .filter(value ->
                                value != null && !value.isBlank()
                        )
                        .distinct()
                        .limit(10)
                        .toList();

        List<String> errors =
                tempo.traces()
                        .stream()
                        .filter(trace ->
                                trace != null
                                        && trace.error() != null
                                        && !trace.error().isBlank()
                        )
                        .map(trace ->
                                clean(trace.error(), 500)
                        )
                        .distinct()
                        .limit(5)
                        .toList();

        return new AiEvidenceBundle.TempoSummary(
                tempo.requestedTraceCount(),
                tempo.foundTraceCount(),
                foundTraceIds,
                errors
        );
    }

    private List<AiEvidenceBundle.KnowledgeExcerpt>
    summarizeKnowledge(
            List<KnowledgeResult> knowledgeResults
    ) {
        if (knowledgeResults == null) {
            return List.of();
        }

        return knowledgeResults.stream()
                .filter(result -> result != null)
                .limit(MAX_KNOWLEDGE_RESULTS)
                .map(result ->
                        new AiEvidenceBundle.KnowledgeExcerpt(
                                clean(result.source(), 200),
                                clean(result.title(), 500),
                                clean(
                                        result.content(),
                                        MAX_KNOWLEDGE_CONTENT
                                ),
                                clean(result.url(), 1_000)
                        )
                )
                .toList();
    }

    private List<AiEvidenceBundle.SourceExcerpt>
    sanitizeSourceExcerpts(
            List<AiEvidenceBundle.SourceExcerpt> sourceExcerpts
    ) {
        if (sourceExcerpts == null) {
            return List.of();
        }

        return sourceExcerpts.stream()
                .filter(excerpt -> excerpt != null)
                .limit(MAX_SOURCE_EXCERPTS)
                .map(excerpt ->
                        new AiEvidenceBundle.SourceExcerpt(
                                clean(excerpt.path(), 1_000),
                                clean(
                                        excerpt.content(),
                                        MAX_SOURCE_CONTENT
                                )
                        )
                )
                .toList();
    }

    private String clean(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }

        String sanitized =
                sanitizer.sanitize(value).trim();

        if (sanitized.length() <= maxLength) {
            return sanitized;
        }

        return sanitized.substring(0, maxLength);
    }

    public AiEvidenceBundle buildValidatedBundle(
            java.util.UUID caseId,
            String bundleVersion,
            java.util.Map<String, String> sections,
            String incidentCommitSha,
            String jenkinsCommitSha,
            java.util.List<String> warnings
    ) {
        String combinedEvidenceText =
                "=== JENKINS-VALIDATED EVIDENCE BUNDLE ===\n"
                        + "Bundle Version: " + bundleVersion + "\n"
                        + "Case ID: " + caseId + "\n"
                        + "Incident Commit: " + (incidentCommitSha != null ? incidentCommitSha : "N/A") + "\n"
                        + "Jenkins Commit: " + (jenkinsCommitSha != null ? jenkinsCommitSha : "N/A") + "\n"
                        + "Commit Match: " + (incidentCommitSha != null && incidentCommitSha.equals(jenkinsCommitSha) ? "MATCH" : "MISMATCH") + "\n"
                        + "\n"
                        + buildSectionText("JIRA Issue", sections.get("jira"))
                        + buildSectionText("Loki Query Plan", sections.get("lokiQueryPlan"))
                        + buildSectionText("Loki Logs", sections.get("lokiLogs"))
                        + buildSectionText("Tempo Trace", sections.get("tempoTrace"))
                        + buildSectionText("Repository Resolution", sections.get("repositoryResolution"))
                        + buildSectionText("Incident Version", sections.get("incidentVersion"))
                        + buildSectionText("Jenkins Build Context", sections.get("jenkinsContext"))
                        + buildSectionText("Jenkins Validation", sections.get("jenkinsValidation"))
                        + buildSectionText("Validated Source Context (Jenkins Commit)", sections.get("validatedSourceContext"))
                        + buildSectionText("Previous Source Context (Incident Commit)", sections.get("previousSourceContext"))
                        + buildSectionText("Deterministic Root Cause", sections.get("deterministicRootCause"));

        java.util.List<String> enhancedGuardrails = new java.util.ArrayList<>();
        enhancedGuardrails.add("This bundle contains evidence from Jenkins-validated source code.");
        enhancedGuardrails.add("The source context was generated from commit: " + (jenkinsCommitSha != null ? jenkinsCommitSha : "unknown"));
        if (incidentCommitSha != null && !incidentCommitSha.equals(jenkinsCommitSha)) {
            enhancedGuardrails.add("WARNING: Jenkins commit differs from incident version commit. Review both contexts.");
        }
        enhancedGuardrails.add("Use only evidence contained in this bundle.");
        enhancedGuardrails.add("Do not invent source files, line numbers, services or configuration values.");
        enhancedGuardrails.add("Clearly state when evidence is insufficient or contradictory.");
        enhancedGuardrails.add("Do not recommend automatic merge or production deployment.");
        enhancedGuardrails.add("Human review is mandatory before code or configuration changes.");
        if (warnings != null && !warnings.isEmpty()) {
            enhancedGuardrails.add("Bundle generation warnings: " + String.join("; ", warnings));
        }

        return new AiEvidenceBundle(
                "jenkins-validated",
                "Jenkins-Validated Root Cause Analysis",
                combinedEvidenceText,
                null,
                new CorrelationSignals(
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of()
                ),
                java.util.List.of(),
                new AiEvidenceBundle.TempoSummary(0, 0, java.util.List.of(), java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                enhancedGuardrails
        );
    }

    private String buildSectionText(String sectionName, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return "\n=== " + sectionName + " ===\n" + content + "\n";
    }
}
