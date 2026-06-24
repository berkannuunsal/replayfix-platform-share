package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GoldenPathEvidenceSnapshotResponse(
        UUID caseId,
        String jiraKey,
        String targetKey,
        String snapshotStatus,
        boolean synthetic,
        Repository repository,
        Jenkins jenkins,
        IncidentVersion incidentVersion,
        Observability observability,
        AiInputBundle aiInputBundle,
        DeterministicRca deterministicRca,
        SourceContractValidation sourceContractValidation,
        String jiraMarkdownPreview,
        String rovoRcaInputBlock,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
    public GoldenPathEvidenceSnapshotResponse {
        jiraKey = safe(jiraKey);
        targetKey = safe(targetKey);
        snapshotStatus = safe(snapshotStatus);
        repository = repository == null ? new Repository("", "", "", "", "") : repository;
        jenkins = jenkins == null ? new Jenkins("", "", "", "", "") : jenkins;
        incidentVersion = incidentVersion == null
                ? new IncidentVersion("", "", "", "", false)
                : incidentVersion;
        observability = observability == null
                ? new Observability(false, false, false, 0, 0)
                : observability;
        aiInputBundle = aiInputBundle == null
                ? new AiInputBundle("", "replayfix-ai-bundle-builder", false, List.of())
                : aiInputBundle;
        deterministicRca = deterministicRca == null
                ? new DeterministicRca("", "", "", List.of(), List.of())
                : deterministicRca;
        sourceContractValidation = sourceContractValidation == null
                ? new SourceContractValidation(false, List.of(), List.of())
                : sourceContractValidation;
        jiraMarkdownPreview = safe(jiraMarkdownPreview);
        rovoRcaInputBlock = safe(rovoRcaInputBlock);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public record Repository(
            String projectKey,
            String repositorySlug,
            String branch,
            String commitSha,
            String resolutionStatus
    ) {
        public Repository {
            projectKey = safe(projectKey);
            repositorySlug = safe(repositorySlug);
            branch = safe(branch);
            commitSha = safe(commitSha);
            resolutionStatus = safe(resolutionStatus);
        }
    }

    public record Jenkins(
            String jobName,
            String buildNumber,
            String buildUrl,
            String commitSha,
            String status
    ) {
        public Jenkins {
            jobName = safe(jobName);
            buildNumber = safe(buildNumber);
            buildUrl = safe(buildUrl);
            commitSha = safe(commitSha);
            status = safe(status);
        }
    }

    public record IncidentVersion(
            String status,
            String source,
            String version,
            String commitSha,
            boolean evidenceAvailable
    ) {
        public IncidentVersion {
            status = safe(status);
            source = safe(source);
            version = safe(version);
            commitSha = safe(commitSha);
        }
    }

    public record Observability(
            boolean lokiEvidenceAvailable,
            boolean tempoEvidenceAvailable,
            boolean traceIdPresent,
            int logEvidenceCount,
            int traceEvidenceCount
    ) {
    }

    public record AiInputBundle(
            String status,
            String source,
            boolean evidenceAvailable,
            List<String> missingEvidence
    ) {
        public AiInputBundle {
            status = safe(status);
            source = safe(source);
            missingEvidence = missingEvidence == null
                    ? List.of()
                    : List.copyOf(missingEvidence);
        }
    }

    public record DeterministicRca(
            String status,
            String summary,
            String confidence,
            List<String> evidenceUsed,
            List<String> missingEvidence
    ) {
        public DeterministicRca {
            status = safe(status);
            summary = safe(summary);
            confidence = safe(confidence);
            evidenceUsed = evidenceUsed == null ? List.of() : List.copyOf(evidenceUsed);
            missingEvidence = missingEvidence == null
                    ? List.of()
                    : List.copyOf(missingEvidence);
        }
    }

    public record SourceContractValidation(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {
        public SourceContractValidation {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
