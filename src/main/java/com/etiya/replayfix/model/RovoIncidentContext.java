package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sanitized incident context for Rovo Incident Commander.
 * GET /api/v1/rovo/incidents/{jiraKey}/context response model.
 */
public record RovoIncidentContext(
        UUID caseId,
        String jiraKey,
        String jiraSummary,
        String jiraDescriptionSummary,
        String applicationKey,
        String targetKey,
        LokiFindings lokiFindings,
        TempoServiceChain tempoServiceChain,
        JenkinsContext jenkinsContext,
        BitbucketContext bitbucketContext,
        SourceContext sourceContext,
        DeterministicRca deterministicRca,
        List<String> missingEvidence,
        Map<String, List<UUID>> evidenceIdsByType,
        Instant incidentTime,
        boolean synthetic
) {
    public record LokiFindings(
            int totalLogEntries,
            List<String> errorSignals,
            List<String> warningSignals,
            String queryUsed,
            String timeRange
    ) {}

    public record TempoServiceChain(
            String rootTraceId,
            List<String> services,
            String failedService,
            String failedOperation,
            int spanCount
    ) {}

    public record JenkinsContext(
            String jobName,
            int buildNumber,
            String commitSha,
            String branch,
            Instant buildTimestamp,
            String buildStatus
    ) {}

    public record BitbucketContext(
            String projectKey,
            String repositorySlug,
            String defaultBranch,
            String analysisTargetBranch,
            String commitSha
    ) {}

    public record SourceContext(
            List<FileContext> files,
            String repositoryCheckoutSha
    ) {}

    public record FileContext(
            String filePath,
            String className,
            List<String> methods,
            int totalLines
    ) {}

    public record DeterministicRca(
            String summary,
            String probableRootCause,
            double confidence,
            String source,
            List<String> evidenceIds
    ) {}
}
