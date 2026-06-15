package com.etiya.replayfix.service;

import com.etiya.replayfix.model.AdaptiveLokiSearchResult;
import com.etiya.replayfix.model.CorrelationSignals;
import com.etiya.replayfix.model.DeterministicRootCauseReport;
import com.etiya.replayfix.model.IncidentSignals;
import com.etiya.replayfix.model.IncidentTimeline;
import com.etiya.replayfix.model.LokiSearchAttempt;
import com.etiya.replayfix.model.RootCauseMetrics;
import com.etiya.replayfix.model.TempoEnrichmentResult;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
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
}
