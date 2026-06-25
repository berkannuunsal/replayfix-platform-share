package com.etiya.replaylab.service;

import com.etiya.replaylab.model.AdaptiveLokiSearchResult;
import com.etiya.replaylab.model.AiEvidenceBundle;
import com.etiya.replaylab.model.CorrelationSignals;
import com.etiya.replaylab.model.DeterministicRootCauseReport;
import com.etiya.replaylab.model.IncidentSignals;
import com.etiya.replaylab.model.IncidentTimeline;
import com.etiya.replaylab.model.IncidentTimelineEvent;
import com.etiya.replaylab.model.LokiSearchAttempt;
import com.etiya.replaylab.model.RootCauseMetrics;
import com.etiya.replaylab.model.TempoEnrichmentResult;
import com.etiya.replaylab.model.IntegrationModels.JiraIssue;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DeterministicRootCauseReportBuilder {

    private static final String STATUS_HYPOTHESIS =
            "HYPOTHESIS";

    public DeterministicRootCauseReport build(
            JiraIssue jiraIssue,
            String plainDescription,
            IncidentSignals incidentSignals,
            AdaptiveLokiSearchResult firstPass,
            CorrelationSignals correlationSignals,
            AdaptiveLokiSearchResult secondPass,
            IncidentTimeline timeline,
            TempoEnrichmentResult tempoEnrichment
    ) {
        String classification =
                classify(
                        jiraIssue,
                        plainDescription,
                        incidentSignals
                );

        RootCauseMetrics metrics =
                createMetrics(
                        firstPass,
                        correlationSignals,
                        secondPass,
                        timeline,
                        tempoEnrichment
                );

        List<String> affectedApplications =
                determineApplications(
                        incidentSignals,
                        timeline
                );

        List<String> supportingEvidence =
                buildSupportingEvidence(
                        incidentSignals,
                        firstPass,
                        correlationSignals,
                        secondPass,
                        timeline,
                        tempoEnrichment
                );

        List<String> missingEvidence =
                buildMissingEvidence(
                        firstPass,
                        correlationSignals,
                        secondPass,
                        timeline,
                        tempoEnrichment
                );

        double confidence =
                calculateConfidence(
                        incidentSignals,
                        firstPass,
                        correlationSignals,
                        secondPass,
                        timeline,
                        tempoEnrichment
                );

        String probableCause =
                buildProbableCause(
                        classification,
                        incidentSignals
                );

        List<String> recommendedActions =
                buildRecommendedActions(
                        classification,
                        incidentSignals
                );

        return new DeterministicRootCauseReport(
                jiraIssue.key(),
                STATUS_HYPOTHESIS,
                classification,
                probableCause,
                confidence,
                affectedApplications,
                supportingEvidence,
                missingEvidence,
                recommendedActions,
                metrics
        );
    }

    private String classify(
            JiraIssue jiraIssue,
            String plainDescription,
            IncidentSignals signals
    ) {
        String combinedText =
                (
                        safe(jiraIssue.summary())
                                + " "
                                + safe(plainDescription)
                                + " "
                                + String.join(
                                        " ",
                                        safeList(
                                                signals.businessTerms()
                                        )
                                )
                ).toLowerCase(Locale.ROOT);

        if (containsHttpStatus(signals, "401")
                || containsHttpStatus(signals, "403")
                || combinedText.contains("unauthorized")
                || combinedText.contains("forbidden")) {

            return "AUTHENTICATION_OR_AUTHORIZATION";
        }

        if (combinedText.contains("timeout")
                || combinedText.contains("timed out")
                || combinedText.contains("read timeout")
                || combinedText.contains("connection timeout")) {

            return "TIMEOUT_OR_CONNECTIVITY";
        }

        if (containsFiveHundredStatus(signals)) {
            return "DOWNSTREAM_SERVICE_FAILURE";
        }

        if (combinedText.contains("nullpointerexception")
                || combinedText.contains("null pointer")
                || combinedText.contains("validation error")
                || combinedText.contains("invalid payload")) {

            return "DATA_OR_VALIDATION_FAILURE";
        }

        if (combinedText.contains("stuck")
                || combinedText.contains("status 12")
                || combinedText.contains("status = 12")
                || combinedText.contains("camunda")
                || combinedText.contains("workflow")) {

            return "WORKFLOW_STATE_INCONSISTENCY";
        }

        return "UNCLASSIFIED";
    }

    private String buildProbableCause(
            String classification,
            IncidentSignals signals
    ) {
        String endpoint =
                firstOrDefault(
                        signals.endpoints(),
                        "the affected downstream endpoint"
                );

        String httpStatus =
                firstOrDefault(
                        signals.httpStatuses(),
                        "an error response"
                );

        return switch (classification) {
            case "AUTHENTICATION_OR_AUTHORIZATION" ->
                    "The call to "
                            + endpoint
                            + " probably failed with HTTP "
                            + httpStatus
                            + " because the calling application did not "
                            + "present valid credentials, used an expired "
                            + "credential, or lacked the required permission. "
                            + "The workflow then appears to have terminated "
                            + "before dependent order and callback steps "
                            + "could complete.";

            case "TIMEOUT_OR_CONNECTIVITY" ->
                    "The affected request probably failed because the "
                            + "downstream service was unreachable or did not "
                            + "respond within the configured timeout. "
                            + "Dependent workflow steps may have remained "
                            + "in an incomplete state.";

            case "DOWNSTREAM_SERVICE_FAILURE" ->
                    "The downstream operation at "
                            + endpoint
                            + " probably returned a server-side failure. "
                            + "The upstream workflow did not recover or "
                            + "complete its dependent operations.";

            case "DATA_OR_VALIDATION_FAILURE" ->
                    "The request or persisted data probably violated an "
                            + "application validation rule or contained a "
                            + "missing value that was not safely handled.";

            case "WORKFLOW_STATE_INCONSISTENCY" ->
                    "The workflow probably terminated or changed state "
                            + "before all dependent activities completed, "
                            + "leaving related orders or callbacks in an "
                            + "intermediate state.";

            default ->
                    "The available evidence identifies the affected flow, "
                            + "but it is not yet sufficient to assign a "
                            + "specific technical root-cause category.";
        };
    }

    private List<String> buildSupportingEvidence(
            IncidentSignals signals,
            AdaptiveLokiSearchResult firstPass,
            CorrelationSignals correlationSignals,
            AdaptiveLokiSearchResult secondPass,
            IncidentTimeline timeline,
            TempoEnrichmentResult tempoEnrichment
    ) {
        List<String> evidence =
                new ArrayList<>();

        if (!safeList(signals.endpoints()).isEmpty()) {
            evidence.add(
                    "Jira description references endpoint(s): "
                            + String.join(
                                    ", ",
                                    signals.endpoints()
                            )
            );
        }

        if (!safeList(signals.httpStatuses()).isEmpty()) {
            evidence.add(
                    "Jira description references HTTP status(es): "
                            + String.join(
                                    ", ",
                                    signals.httpStatuses()
                            )
            );
        }

        if (!safeList(signals.errorCodes()).isEmpty()) {
            evidence.add(
                    "Jira description references error code(s): "
                            + String.join(
                                    ", ",
                                    signals.errorCodes()
                            )
            );
        }

        addSuccessfulAttemptEvidence(
                evidence,
                "First-pass Loki",
                firstPass
        );

        addSuccessfulAttemptEvidence(
                evidence,
                "Second-pass Loki",
                secondPass
        );

        if (correlationSignals != null
                && correlationSignals.totalCount() > 0) {

            evidence.add(
                    "Loki logs produced "
                            + correlationSignals.totalCount()
                            + " correlation value(s), including "
                            + correlationSignals.traceIds().size()
                            + " trace ID(s) and "
                            + correlationSignals.orderIds().size()
                            + " order ID(s)."
            );
        }

        if (timeline != null
                && timeline.eventCount() > 0) {

            evidence.add(
                    "Incident timeline contains "
                            + timeline.eventCount()
                            + " unique event(s) across "
                            + timeline.applicationCounts().size()
                            + " application(s)."
            );

            if (timeline.httpStatusCounts() != null
                    && !timeline.httpStatusCounts().isEmpty()) {

                evidence.add(
                        "Timeline HTTP status distribution: "
                                + timeline.httpStatusCounts()
                );
            }
        }

        if (tempoEnrichment != null
                && tempoEnrichment.foundTraceCount() > 0) {

            evidence.add(
                    "Tempo returned "
                            + tempoEnrichment.foundTraceCount()
                            + " trace(s) from "
                            + tempoEnrichment.requestedTraceCount()
                            + " requested trace ID(s)."
            );
        }

        return evidence;
    }

    private void addSuccessfulAttemptEvidence(
            List<String> evidence,
            String label,
            AdaptiveLokiSearchResult result
    ) {
        if (result == null
                || result.attempts() == null) {
            return;
        }

        result.attempts()
                .stream()
                .filter(attempt ->
                        attempt.resultCount() > 0
                )
                .limit(5)
                .forEach(attempt ->
                        evidence.add(
                                label
                                        + " query matched "
                                        + attempt.resultCount()
                                        + " row(s): "
                                        + attempt.reason()
                        )
                );
    }

    private List<String> buildMissingEvidence(
            AdaptiveLokiSearchResult firstPass,
            CorrelationSignals correlationSignals,
            AdaptiveLokiSearchResult secondPass,
            IncidentTimeline timeline,
            TempoEnrichmentResult tempoEnrichment
    ) {
        List<String> missing =
                new ArrayList<>();

        if (firstPass == null
                || firstPass.logs() == null
                || firstPass.logs().isEmpty()) {

            missing.add(
                    "No first-pass Loki log matched the Jira-derived queries."
            );
        }

        if (correlationSignals == null
                || correlationSignals.totalCount() == 0) {

            missing.add(
                    "No traceId, orderId, correlationId, "
                            + "processInstanceId, businessKey or requestId "
                            + "could be extracted from the matched logs."
            );
        }

        if (secondPass == null
                || secondPass.logs() == null
                || secondPass.logs().isEmpty()) {

            missing.add(
                    "Second-pass correlation search did not return "
                            + "additional log evidence."
            );
        }

        if (timeline == null
                || timeline.eventCount() == 0) {

            missing.add(
                    "An incident timeline could not be built from the logs."
            );
        }

        if (tempoEnrichment == null
                || tempoEnrichment.requestedTraceCount() == 0) {

            missing.add(
                    "No trace ID was available for Tempo enrichment."
            );
        } else if (tempoEnrichment.foundTraceCount() == 0) {
            missing.add(
                    "Tempo did not return a trace for the extracted trace IDs."
            );
        }

        int failedQueryCount =
                countFailedQueries(firstPass)
                        + countFailedQueries(secondPass);

        if (failedQueryCount > 0) {
            missing.add(
                    failedQueryCount
                            + " Loki query or queries failed and may "
                            + "have reduced evidence coverage."
            );
        }

        return missing;
    }

    private List<String> determineApplications(
            IncidentSignals signals,
            IncidentTimeline timeline
    ) {
        Set<String> applications =
                new LinkedHashSet<>();

        applications.addAll(
                safeList(
                        signals.serviceHints()
                )
        );

        if (timeline != null
                && timeline.applicationCounts() != null) {

            timeline.applicationCounts()
                    .keySet()
                    .stream()
                    .filter(value ->
                            value != null
                                    && !value.isBlank()
                                    && !"unknown".equalsIgnoreCase(value)
                    )
                    .forEach(applications::add);
        }

        return new ArrayList<>(applications);
    }

    private List<String> buildRecommendedActions(
            String classification,
            IncidentSignals signals
    ) {
        String endpoint =
                firstOrDefault(
                        signals.endpoints(),
                        "the affected endpoint"
                );

        List<String> actions =
                new ArrayList<>();

        switch (classification) {
            case "AUTHENTICATION_OR_AUTHORIZATION" -> {
                actions.add(
                        "Verify the credential, token or service account "
                                + "used when calling "
                                + endpoint
                                + "."
                );

                actions.add(
                        "Check credential expiry, environment-specific "
                                + "configuration and required endpoint roles."
                );

                actions.add(
                        "Add an integration test that verifies the calling "
                                + "service can authenticate before the "
                                + "workflow deletes or completes its process."
                );

                actions.add(
                        "Prevent the workflow from terminating permanently "
                                + "when the downstream call returns 401 or 403; "
                                + "use an explicit retry or recoverable state."
                );
            }

            case "TIMEOUT_OR_CONNECTIVITY" -> {
                actions.add(
                        "Verify network connectivity, DNS, service routing "
                                + "and timeout settings."
                );

                actions.add(
                        "Add bounded retry and circuit-breaker behavior."
                );
            }

            case "DOWNSTREAM_SERVICE_FAILURE" -> {
                actions.add(
                        "Inspect the downstream service logs and deployment "
                                + "changes for the incident time."
                );

                actions.add(
                        "Add retry or compensation logic for recoverable "
                                + "server-side failures."
                );
            }

            case "DATA_OR_VALIDATION_FAILURE" -> {
                actions.add(
                        "Reproduce the request using the minimum affected "
                                + "database records and sanitized payload."
                );

                actions.add(
                        "Add validation and a regression test before the "
                                + "failing code path."
                );
            }

            case "WORKFLOW_STATE_INCONSISTENCY" -> {
                actions.add(
                        "Inspect Camunda execution and business-key state "
                                + "before and after the failure."
                );

                actions.add(
                        "Introduce an explicit recoverable failure state "
                                + "instead of prematurely deleting the process."
                );
            }

            default -> actions.add(
                    "Collect a concrete orderId, traceId or incident timestamp "
                            + "and rerun the correlation search."
            );
        }

        actions.add(
                "Require human review before accepting the hypothesis "
                        + "or applying any generated patch."
        );

        return actions;
    }

    private double calculateConfidence(
            IncidentSignals signals,
            AdaptiveLokiSearchResult firstPass,
            CorrelationSignals correlationSignals,
            AdaptiveLokiSearchResult secondPass,
            IncidentTimeline timeline,
            TempoEnrichmentResult tempoEnrichment
    ) {
        double score = 0.20;

        if (!safeList(signals.endpoints()).isEmpty()) {
            score += 0.15;
        }

        if (!safeList(signals.httpStatuses()).isEmpty()) {
            score += 0.15;
        }

        if (hasHighPriorityMatch(firstPass)) {
            score += 0.20;
        } else if (hasAnyLog(firstPass)) {
            score += 0.10;
        }

        if (correlationSignals != null
                && correlationSignals.totalCount() > 0) {
            score += 0.10;
        }

        if (hasAnyLog(secondPass)) {
            score += 0.10;
        }

        if (timeline != null
                && timeline.eventCount() > 0) {
            score += 0.05;
        }

        if (tempoEnrichment != null
                && tempoEnrichment.foundTraceCount() > 0) {
            score += 0.10;
        }

        int failedQueries =
                countFailedQueries(firstPass)
                        + countFailedQueries(secondPass);

        score -= Math.min(
                0.15,
                failedQueries * 0.02
        );

        score = Math.max(
                0.10,
                Math.min(0.95, score)
        );

        return Math.round(score * 100.0) / 100.0;
    }

    private RootCauseMetrics createMetrics(
            AdaptiveLokiSearchResult firstPass,
            CorrelationSignals correlationSignals,
            AdaptiveLokiSearchResult secondPass,
            IncidentTimeline timeline,
            TempoEnrichmentResult tempoEnrichment
    ) {
        return new RootCauseMetrics(
                countQueries(firstPass),
                countMatchedRows(firstPass),
                countLogs(firstPass),

                correlationSignals == null
                        ? 0
                        : correlationSignals.totalCount(),

                countQueries(secondPass),
                countMatchedRows(secondPass),
                countLogs(secondPass),

                timeline == null
                        ? 0
                        : timeline.eventCount(),

                tempoEnrichment == null
                        ? 0
                        : tempoEnrichment.requestedTraceCount(),

                tempoEnrichment == null
                        ? 0
                        : tempoEnrichment.foundTraceCount(),

                countFailedQueries(firstPass)
                        + countFailedQueries(secondPass)
        );
    }

    private boolean hasHighPriorityMatch(
            AdaptiveLokiSearchResult result
    ) {
        if (result == null
                || result.attempts() == null) {
            return false;
        }

        return result.attempts()
                .stream()
                .anyMatch(attempt ->
                        attempt.priority() >= 90
                                && attempt.resultCount() > 0
                );
    }

    private boolean hasAnyLog(
            AdaptiveLokiSearchResult result
    ) {
        return result != null
                && result.logs() != null
                && !result.logs().isEmpty();
    }

    private int countQueries(
            AdaptiveLokiSearchResult result
    ) {
        return result == null
                || result.attempts() == null
                ? 0
                : result.attempts().size();
    }

    private int countLogs(
            AdaptiveLokiSearchResult result
    ) {
        return result == null
                || result.logs() == null
                ? 0
                : result.logs().size();
    }

    private int countMatchedRows(
            AdaptiveLokiSearchResult result
    ) {
        if (result == null
                || result.attempts() == null) {
            return 0;
        }

        return result.attempts()
                .stream()
                .mapToInt(
                        LokiSearchAttempt::resultCount
                )
                .sum();
    }

    private int countFailedQueries(
            AdaptiveLokiSearchResult result
    ) {
        if (result == null
                || result.attempts() == null) {
            return 0;
        }

        return (int) result.attempts()
                .stream()
                .filter(attempt ->
                        attempt.error() != null
                                && !attempt.error().isBlank()
                )
                .count();
    }

    private boolean containsHttpStatus(
            IncidentSignals signals,
            String expected
    ) {
        return safeList(signals.httpStatuses())
                .contains(expected);
    }

    private boolean containsFiveHundredStatus(
            IncidentSignals signals
    ) {
        return safeList(signals.httpStatuses())
                .stream()
                .anyMatch(value -> {
                    try {
                        int status =
                                Integer.parseInt(value);

                        return status >= 500
                                && status <= 599;
                    } catch (Exception ignored) {
                        return false;
                    }
                });
    }

    private String firstOrDefault(
            List<String> values,
            String fallback
    ) {
        return values == null
                || values.isEmpty()
                ? fallback
                : values.get(0);
    }

    private <T> List<T> safeList(
            List<T> values
    ) {
        return values == null
                ? List.of()
                : values;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public DeterministicRootCauseReport buildFromBundle(
            AiEvidenceBundle bundle
    ) {
        if (bundle == null) {
            throw new IllegalArgumentException(
                    "AI evidence bundle is null."
            );
        }

        String classification =
                classifyFromBundle(bundle);

        RootCauseMetrics metrics =
                createBundleMetrics(bundle);

        String probableCause =
                determineProbableCauseFromBundle(
                        bundle,
                        classification
                );

        double confidence =
                calculateBundleConfidence(
                        bundle,
                        classification
                );

        List<String> supportingEvidence =
                extractSupportingEvidenceFromBundle(
                        bundle
                );

        List<String> missingEvidence =
                identifyMissingEvidenceFromBundle(
                        bundle
                );

        List<String> recommendedActions =
                generateRecommendedActionsFromBundle(
                        bundle,
                        classification
                );

        List<String> affectedApplications =
                extractAffectedApplicationsFromBundle(
                        bundle
                );

        return new DeterministicRootCauseReport(
                bundle.jiraKey(),
                STATUS_HYPOTHESIS,
                classification,
                probableCause,
                confidence,
                affectedApplications,
                supportingEvidence,
                missingEvidence,
                recommendedActions,
                metrics
        );
    }

    private String classifyFromBundle(
            AiEvidenceBundle bundle
    ) {
        String description =
                bundle.plainDescription()
                        .toLowerCase();

        if (description.contains("timeout")
                || description.contains("timed out")) {
            return "TIMEOUT";
        }

        if (description.contains("connection")
                && (description.contains("refused")
                        || description.contains("failed"))) {
            return "CONNECTION_FAILURE";
        }

        if (description.contains("null pointer")
                || description.contains("nullpointerexception")) {
            return "NULL_POINTER";
        }

        if (description.contains("authentication")
                || description.contains("unauthorized")
                || description.contains("401")) {
            return "AUTHENTICATION_FAILURE";
        }

        if (description.contains("500")
                || description.contains("internal server error")) {
            return "INTERNAL_SERVER_ERROR";
        }

        return "UNCLASSIFIED";
    }

    private RootCauseMetrics createBundleMetrics(
            AiEvidenceBundle bundle
    ) {
        int timelineEventCount =
                bundle.timelineEvents() == null
                        ? 0
                        : bundle.timelineEvents().size();

        int correlationSignalCount =
                bundle.correlations() == null
                        ? 0
                        : countCorrelationSignals(
                                bundle.correlations()
                        );

        int tempoTraceCount =
                bundle.tempo() == null
                        ? 0
                        : bundle.tempo().foundTraceCount();

        int sourceExcerptCount =
                bundle.sourceExcerpts() == null
                        ? 0
                        : bundle.sourceExcerpts().size();

        return new RootCauseMetrics(
                0,
                0,
                0,
                correlationSignalCount,
                0,
                0,
                sourceExcerptCount,
                timelineEventCount,
                tempoTraceCount,
                tempoTraceCount,
                0
        );
    }

    private int countCorrelationSignals(
            CorrelationSignals signals
    ) {
        return safeList(signals.traceIds()).size()
                + safeList(signals.orderIds()).size()
                + safeList(signals.correlationIds()).size()
                + safeList(signals.processInstanceIds()).size()
                + safeList(signals.businessKeys()).size()
                + safeList(signals.requestIds()).size();
    }

    private String determineProbableCauseFromBundle(
            AiEvidenceBundle bundle,
            String classification
    ) {
        StringBuilder cause =
                new StringBuilder();

        cause.append("Based on Jenkins-validated source context");

        if (bundle.deterministicReport() != null
                && bundle.deterministicReport()
                        .probableCause() != null) {
            cause.append(": ")
                    .append(bundle.deterministicReport()
                            .probableCause());
        } else {
            cause.append(", incident classification suggests ")
                    .append(classification.replace("_", " ").toLowerCase())
                    .append(".");
        }

        if (bundle.timelineEvents() != null
                && !bundle.timelineEvents().isEmpty()) {
            cause.append(" Timeline analysis identifies ")
                    .append(bundle.timelineEvents().size())
                    .append(" relevant event(s).");
        }

        return cause.toString();
    }

    private double calculateBundleConfidence(
            AiEvidenceBundle bundle,
            String classification
    ) {
        double confidence = 0.3;

        if (bundle.timelineEvents() != null
                && !bundle.timelineEvents().isEmpty()) {
            confidence += 0.2;
        }

        if (bundle.correlations() != null
                && countCorrelationSignals(
                        bundle.correlations()
                ) > 0) {
            confidence += 0.15;
        }

        if (bundle.tempo() != null
                && bundle.tempo().foundTraceCount() > 0) {
            confidence += 0.15;
        }

        if (bundle.sourceExcerpts() != null
                && !bundle.sourceExcerpts().isEmpty()) {
            confidence += 0.2;
        }

        if (bundle.deterministicReport() != null) {
            confidence = Math.max(
                    confidence,
                    bundle.deterministicReport()
                            .confidence()
            );
        }

        return Math.min(confidence, 0.95);
    }

    private List<String> extractSupportingEvidenceFromBundle(
            AiEvidenceBundle bundle
    ) {
        List<String> evidence =
                new ArrayList<>();

        if (bundle.timelineEvents() != null
                && !bundle.timelineEvents().isEmpty()) {
            evidence.add(
                    bundle.timelineEvents().size()
                            + " timeline event(s) correlated with incident window"
            );
        }

        if (bundle.correlations() != null) {
            int signalCount =
                    countCorrelationSignals(
                            bundle.correlations()
                    );

            if (signalCount > 0) {
                evidence.add(
                        signalCount
                                + " correlation signal(s) identified"
                );
            }
        }

        if (bundle.tempo() != null
                && bundle.tempo().foundTraceCount() > 0) {
            evidence.add(
                    bundle.tempo().foundTraceCount()
                            + " distributed trace(s) found"
            );
        }

        if (bundle.sourceExcerpts() != null
                && !bundle.sourceExcerpts().isEmpty()) {
            evidence.add(
                    bundle.sourceExcerpts().size()
                            + " relevant source code excerpt(s) from Jenkins-validated commit"
            );
        }

        if (bundle.plainDescription()
                .contains("Jenkins Commit:")
                && bundle.plainDescription()
                        .contains("MISMATCH")) {
            evidence.add(
                    "Source context regenerated from Jenkins deployment commit (differs from incident version)"
            );
        }

        return evidence;
    }

    private List<String> identifyMissingEvidenceFromBundle(
            AiEvidenceBundle bundle
    ) {
        List<String> missing =
                new ArrayList<>();

        if (bundle.tempo() == null
                || bundle.tempo().foundTraceCount() == 0) {
            missing.add(
                    "Distributed tracing data not available"
            );
        }

        if (bundle.sourceExcerpts() == null
                || bundle.sourceExcerpts().isEmpty()) {
            missing.add(
                    "Source code context not available"
            );
        }

        if (bundle.knowledge() == null
                || bundle.knowledge().isEmpty()) {
            missing.add(
                    "Knowledge base articles not available"
            );
        }

        return missing;
    }

    private List<String> generateRecommendedActionsFromBundle(
            AiEvidenceBundle bundle,
            String classification
    ) {
        List<String> actions =
                new ArrayList<>();

        actions.add(
                "Verify root cause hypothesis with regression test"
        );

        if (bundle.plainDescription()
                .contains("MISMATCH")) {
            actions.add(
                    "Review differences between incident commit and Jenkins deployment commit"
            );
        }

        if ("TIMEOUT".equals(classification)) {
            actions.add(
                    "Increase timeout configuration or optimize slow operation"
            );
        } else if ("CONNECTION_FAILURE".equals(classification)) {
            actions.add(
                    "Verify service availability and network connectivity"
            );
        } else if ("AUTHENTICATION_FAILURE".equals(classification)) {
            actions.add(
                    "Validate authentication token propagation and expiration"
            );
        }

        actions.add(
                "Deploy fix to non-production environment for validation"
        );

        actions.add(
                "Require human approval before production deployment"
        );

        return actions;
    }

    private List<String> extractAffectedApplicationsFromBundle(
            AiEvidenceBundle bundle
    ) {
        List<String> applications =
                new ArrayList<>();

        if (bundle.timelineEvents() != null) {
            applications.addAll(
                    bundle.timelineEvents()
                            .stream()
                            .map(IncidentTimelineEvent::application)
                            .filter(app ->
                                    app != null && !app.isBlank()
                            )
                            .distinct()
                            .toList()
            );
        }

        if (applications.isEmpty()) {
            applications.add("unknown");
        }

        return applications;
    }
}
