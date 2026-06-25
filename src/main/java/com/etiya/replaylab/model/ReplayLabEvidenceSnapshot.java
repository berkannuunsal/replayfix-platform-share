package com.etiya.replaylab.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReplayLabEvidenceSnapshot(
        String schemaVersion,
        UUID caseId,
        String jiraKey,
        String targetKey,
        boolean synthetic,
        RepositoryInfo repository,
        JenkinsInfo jenkins,
        IncidentVersionInfo incidentVersion,
        RuntimeEvidenceInfo runtimeEvidence,
        SourceContextInfo sourceContext,
        DeterministicRcaInfo deterministicRca,
        Map<String, List<UUID>> evidenceIds,
        GuardrailsInfo guardrails
) {
    public static final String SCHEMA_VERSION = "1.0";

    public record RepositoryInfo(
            String projectKey,
            String repositorySlug,
            String sourceBranch
    ) {}

    public record JenkinsInfo(
            String jobName,
            Integer buildNumber,
            String commitSha
    ) {}

    public record IncidentVersionInfo(
            String jenkinsCommitSha,
            String bitbucketCommitSha,
            String checkoutCommitSha,
            boolean exactMatch
    ) {}

    public record RuntimeEvidenceInfo(
            int lokiMatchedRowCount,
            int lokiFailedQueryCount,
            List<String> extractedTraceIds,
            List<String> extractedOrderIds,
            List<String> extractedCorrelationIds,
            int tempoRequestedTraceCount,
            int tempoFoundTraceCount
    ) {}

    public record SourceContextInfo(
            int scannedFileCount,
            int matchedFileCount,
            List<MatchedFile> matchedFiles
    ) {
        public record MatchedFile(
                String filePath,
                String snippet
        ) {}
    }

    public record DeterministicRcaInfo(
            String classification,
            String probableCause,
            double confidence,
            List<String> affectedApplications,
            List<String> supportingEvidence,
            List<String> missingEvidence,
            List<String> recommendedActions
    ) {}

    public record GuardrailsInfo(
            boolean evidenceOnly,
            boolean noAutomaticMerge,
            boolean noProductionDeployment,
            boolean humanApprovalRequired
    ) {}
}
