package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotJiraPreviewResponse;
import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotResponse;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.DeterministicRootCauseReport;
import com.etiya.replayfix.model.IncidentVersionResolution;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
import com.etiya.replayfix.model.JenkinsCaseEvidence;
import com.etiya.replayfix.model.RepositoryResolutionResult;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GoldenPathEvidenceSnapshotService {

    static final String JENKINS_BUILD_SOURCE = "jenkins-evidence-collector";
    static final String INCIDENT_VERSION_SOURCE = "jenkins-incident-version-validator";
    static final String AI_INPUT_BUNDLE_SOURCE = "replayfix-ai-bundle-builder";

    private static final List<String> SENSITIVE_MARKERS = List.of(
            "authorization",
            "cookie",
            "password",
            "token",
            "secret",
            "rawpayload",
            "raw payload",
            "reasoning_content"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public GoldenPathEvidenceSnapshotService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public GoldenPathEvidenceSnapshotResponse snapshot(
            UUID caseId,
            boolean includeRawEvidence,
            boolean includeRovoBlock,
            boolean includeJiraMarkdown,
            boolean includeDeterministicRca
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        List<EvidenceEntity> evidence = evidenceRepository
                .findByCaseIdOrderByCreatedAtAsc(caseId);

        ContractResult contract = validateContracts(evidence);
        GoldenPathEvidenceSnapshotResponse.Repository repository =
                repository(evidence);
        GoldenPathEvidenceSnapshotResponse.Jenkins jenkins =
                jenkins(evidence);
        GoldenPathEvidenceSnapshotResponse.IncidentVersion incidentVersion =
                incidentVersion(evidence);
        GoldenPathEvidenceSnapshotResponse.Observability observability =
                observability(evidence, replayCase);
        GoldenPathEvidenceSnapshotResponse.AiInputBundle aiInputBundle =
                aiInputBundle(evidence, contract);
        GoldenPathEvidenceSnapshotResponse.DeterministicRca deterministicRca =
                includeDeterministicRca
                        ? deterministicRca(evidence)
                        : new GoldenPathEvidenceSnapshotResponse.DeterministicRca(
                                "NOT_INCLUDED",
                                "",
                                "",
                                List.of(),
                                List.of()
                        );

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>(contract.warnings());
        if (!contract.errors().isEmpty()) {
            blockers.add("SOURCE_CONTRACT_MISMATCH");
        }
        if (!aiInputBundle.evidenceAvailable()) {
            warnings.add("AI_INPUT_BUNDLE_MISSING");
        }
        if (!incidentVersion.evidenceAvailable()) {
            warnings.add("INCIDENT_VERSION_MISSING");
        }
        if (!observability.lokiEvidenceAvailable()) {
            warnings.add("LOKI_EVIDENCE_MISSING");
        }
        if (!observability.tempoEvidenceAvailable()) {
            warnings.add("TEMPO_EVIDENCE_MISSING");
        }

        String status = !blockers.isEmpty()
                ? "BLOCKED"
                : warnings.isEmpty() ? "READY" : "PARTIAL";
        String jiraMarkdown = includeJiraMarkdown
                ? jiraMarkdown(
                        replayCase,
                        repository,
                        jenkins,
                        incidentVersion,
                        observability,
                        deterministicRca
                )
                : "";
        String rovoBlock = includeRovoBlock
                ? rovoBlock(
                        replayCase,
                        repository,
                        jenkins,
                        incidentVersion,
                        observability,
                        deterministicRca
                )
                : "";

        return new GoldenPathEvidenceSnapshotResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                status,
                replayCase.isSynthetic(),
                repository,
                jenkins,
                incidentVersion,
                observability,
                aiInputBundle,
                deterministicRca,
                new GoldenPathEvidenceSnapshotResponse.SourceContractValidation(
                        contract.errors().isEmpty(),
                        contract.errors(),
                        contract.warnings()
                ),
                sanitize(jiraMarkdown),
                sanitize(rovoBlock),
                unique(blockers),
                unique(warnings),
                nextActions(status, blockers, warnings),
                Instant.now()
        );
    }

    public GoldenPathEvidenceSnapshotJiraPreviewResponse jiraPreview(UUID caseId) {
        GoldenPathEvidenceSnapshotResponse snapshot =
                snapshot(caseId, false, false, true, true);
        return new GoldenPathEvidenceSnapshotJiraPreviewResponse(
                snapshot.caseId(),
                snapshot.jiraKey(),
                snapshot.jiraMarkdownPreview(),
                snapshot.sourceContractValidation().valid()
                        && !"BLOCKED".equals(snapshot.snapshotStatus()),
                snapshot.blockers(),
                snapshot.warnings()
        );
    }

    private ContractResult validateContracts(List<EvidenceEntity> evidence) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        requireCanonical(
                evidence,
                EvidenceType.JENKINS_BUILD_CONTEXT,
                JENKINS_BUILD_SOURCE,
                errors
        );
        requireCanonical(
                evidence,
                EvidenceType.INCIDENT_VERSION,
                INCIDENT_VERSION_SOURCE,
                errors
        );
        requireCanonical(
                evidence,
                EvidenceType.AI_INPUT_BUNDLE,
                AI_INPUT_BUNDLE_SOURCE,
                errors
        );
        boolean deterministicExists = latest(evidence, EvidenceType.DETERMINISTIC_ROOT_CAUSE, null)
                .isPresent();
        boolean canonicalBundleExists = latest(
                evidence,
                EvidenceType.AI_INPUT_BUNDLE,
                AI_INPUT_BUNDLE_SOURCE
        ).isPresent();
        if (deterministicExists && !canonicalBundleExists) {
            errors.add("SOURCE_CONTRACT_MISMATCH:DETERMINISTIC_ROOT_CAUSE_REQUIRES_CANONICAL_AI_INPUT_BUNDLE");
        }
        return new ContractResult(unique(errors), unique(warnings));
    }

    private void requireCanonical(
            List<EvidenceEntity> evidence,
            EvidenceType type,
            String source,
            List<String> errors
    ) {
        boolean any = evidence.stream().anyMatch(item -> item.getEvidenceType() == type);
        boolean canonical = latest(evidence, type, source).isPresent();
        if (any && !canonical) {
            errors.add("SOURCE_CONTRACT_MISMATCH:" + type + ":expectedSource=" + source);
        }
    }

    private GoldenPathEvidenceSnapshotResponse.Repository repository(
            List<EvidenceEntity> evidence
    ) {
        Optional<EvidenceEntity> item =
                latest(evidence, EvidenceType.REPOSITORY_RESOLUTION, null);
        if (item.isEmpty()) {
            return new GoldenPathEvidenceSnapshotResponse.Repository(
                    "",
                    "",
                    "",
                    "",
                    "MISSING"
            );
        }
        try {
            RepositoryResolutionResult result = objectMapper.readValue(
                    text(item.get()),
                    RepositoryResolutionResult.class
            );
            return new GoldenPathEvidenceSnapshotResponse.Repository(
                    result.projectKey(),
                    result.primaryRepositorySlug(),
                    textField(item.get(), "sourceBranch", "branch", "defaultBranch"),
                    "",
                    result.hasSelection() ? "RESOLVED" : "PARTIAL"
            );
        } catch (Exception exception) {
            Map<String, Object> map = map(item.get());
            return new GoldenPathEvidenceSnapshotResponse.Repository(
                    string(map, "projectKey"),
                    firstNonBlank(string(map, "repositorySlug"), string(map, "primaryRepositorySlug")),
                    firstNonBlank(string(map, "sourceBranch"), string(map, "branch")),
                    "",
                    isBlank(string(map, "repositorySlug")) ? "PARTIAL" : "RESOLVED"
            );
        }
    }

    private GoldenPathEvidenceSnapshotResponse.Jenkins jenkins(
            List<EvidenceEntity> evidence
    ) {
        Optional<EvidenceEntity> item = latest(
                evidence,
                EvidenceType.JENKINS_BUILD_CONTEXT,
                JENKINS_BUILD_SOURCE
        );
        if (item.isEmpty()) {
            return new GoldenPathEvidenceSnapshotResponse.Jenkins("", "", "", "", "MISSING");
        }
        try {
            JenkinsCaseEvidence parsed = objectMapper.readValue(
                    text(item.get()),
                    JenkinsCaseEvidence.class
            );
            JenkinsBuildSnapshot build = parsed.build() == null
                    ? parsed.image()
                    : parsed.build();
            return build == null
                    ? new GoldenPathEvidenceSnapshotResponse.Jenkins("", "", "", "", "PARTIAL")
                    : new GoldenPathEvidenceSnapshotResponse.Jenkins(
                            build.jobName(),
                            build.buildNumber() == null ? "" : build.buildNumber().toString(),
                            build.url(),
                            build.commitSha(),
                            firstNonBlank(build.result(), "AVAILABLE")
                    );
        } catch (Exception exception) {
            Map<String, Object> map = map(item.get());
            return new GoldenPathEvidenceSnapshotResponse.Jenkins(
                    string(map, "jobName"),
                    string(map, "buildNumber"),
                    string(map, "buildUrl", "url"),
                    string(map, "commitSha"),
                    firstNonBlank(string(map, "status", "result"), "AVAILABLE")
            );
        }
    }

    private GoldenPathEvidenceSnapshotResponse.IncidentVersion incidentVersion(
            List<EvidenceEntity> evidence
    ) {
        Optional<EvidenceEntity> item = latest(
                evidence,
                EvidenceType.INCIDENT_VERSION,
                INCIDENT_VERSION_SOURCE
        );
        if (item.isEmpty()) {
            return new GoldenPathEvidenceSnapshotResponse.IncidentVersion(
                    "MISSING",
                    INCIDENT_VERSION_SOURCE,
                    "",
                    "",
                    false
            );
        }
        try {
            IncidentVersionResolution parsed = objectMapper.readValue(
                    text(item.get()),
                    IncidentVersionResolution.class
            );
            return new GoldenPathEvidenceSnapshotResponse.IncidentVersion(
                    parsed.exactMatch() ? "MATCHED" : "RESOLVED",
                    item.get().getSource(),
                    firstNonBlank(parsed.resolvedTag(), parsed.requestedImageTag()),
                    parsed.resolvedCommitSha(),
                    true
            );
        } catch (Exception exception) {
            Map<String, Object> map = map(item.get());
            return new GoldenPathEvidenceSnapshotResponse.IncidentVersion(
                    firstNonBlank(string(map, "status"), "AVAILABLE"),
                    item.get().getSource(),
                    string(map, "version", "resolvedTag", "requestedImageTag"),
                    string(map, "commitSha", "resolvedCommitSha", "incidentVersionCommitSha"),
                    true
            );
        }
    }

    private GoldenPathEvidenceSnapshotResponse.Observability observability(
            List<EvidenceEntity> evidence,
            ReplayCaseEntity replayCase
    ) {
        int loki = (int) evidence.stream()
                .filter(item -> item.getEvidenceType() == EvidenceType.LOKI_LOG
                        || item.getEvidenceType() == EvidenceType.LOKI_LOGS)
                .count();
        int tempo = (int) evidence.stream()
                .filter(item -> item.getEvidenceType() == EvidenceType.TEMPO_TRACE
                        || item.getEvidenceType() == EvidenceType.TEMPO_ENRICHMENT)
                .count();
        boolean tracePresent = !isBlank(replayCase.getTraceId())
                || evidence.stream()
                .map(this::text)
                .anyMatch(value -> value.contains("traceId")
                        || value.contains("trace-id"));
        return new GoldenPathEvidenceSnapshotResponse.Observability(
                loki > 0,
                tempo > 0,
                tracePresent,
                loki,
                tempo
        );
    }

    private GoldenPathEvidenceSnapshotResponse.AiInputBundle aiInputBundle(
            List<EvidenceEntity> evidence,
            ContractResult contract
    ) {
        boolean available = latest(
                evidence,
                EvidenceType.AI_INPUT_BUNDLE,
                AI_INPUT_BUNDLE_SOURCE
        ).isPresent();
        List<String> missing = new ArrayList<>();
        if (!available) {
            missing.add("AI_INPUT_BUNDLE");
        }
        for (String error : contract.errors()) {
            if (error.contains("AI_INPUT_BUNDLE")) {
                missing.add(error);
            }
        }
        return new GoldenPathEvidenceSnapshotResponse.AiInputBundle(
                available ? "AVAILABLE" : "MISSING",
                AI_INPUT_BUNDLE_SOURCE,
                available,
                unique(missing)
        );
    }

    private GoldenPathEvidenceSnapshotResponse.DeterministicRca deterministicRca(
            List<EvidenceEntity> evidence
    ) {
        Optional<EvidenceEntity> item = latest(
                evidence,
                EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                null
        );
        if (item.isEmpty()) {
            return new GoldenPathEvidenceSnapshotResponse.DeterministicRca(
                    "MISSING",
                    "",
                    "",
                    List.of(),
                    List.of("DETERMINISTIC_ROOT_CAUSE")
            );
        }
        try {
            DeterministicRootCauseReport report = objectMapper.readValue(
                    text(item.get()),
                    DeterministicRootCauseReport.class
            );
            return new GoldenPathEvidenceSnapshotResponse.DeterministicRca(
                    firstNonBlank(report.status(), "HYPOTHESIS"),
                    report.probableCause(),
                    String.valueOf(report.confidence()),
                    report.supportingEvidence(),
                    report.missingEvidence()
            );
        } catch (Exception exception) {
            Map<String, Object> map = map(item.get());
            return new GoldenPathEvidenceSnapshotResponse.DeterministicRca(
                    firstNonBlank(string(map, "status"), "HYPOTHESIS"),
                    string(map, "summary", "probableCause", "probableRootCause", "rootCause"),
                    firstNonBlank(string(map, "confidence"), String.valueOf(
                            item.get().getConfidence() == null ? "" : item.get().getConfidence()
                    )),
                    stringList(map.get("evidenceUsed")),
                    stringList(map.get("missingEvidence"))
            );
        }
    }

    private String jiraMarkdown(
            ReplayCaseEntity replayCase,
            GoldenPathEvidenceSnapshotResponse.Repository repository,
            GoldenPathEvidenceSnapshotResponse.Jenkins jenkins,
            GoldenPathEvidenceSnapshotResponse.IncidentVersion incidentVersion,
            GoldenPathEvidenceSnapshotResponse.Observability observability,
            GoldenPathEvidenceSnapshotResponse.DeterministicRca rca
    ) {
        return """
                ReplayFix Evidence Snapshot

                Jira:
                - Key: %s
                - Target: %s
                - Synthetic / real: %s

                Repository:
                - Project: %s
                - Repo: %s
                - Branch: %s
                - Commit SHA: %s

                Jenkins:
                - Job: %s
                - Build number: %s
                - Build status: %s
                - Commit SHA: %s

                Incident Version:
                - Version / commit: %s / %s
                - Source: %s
                - Status: %s

                Observability:
                - Loki evidence: %s
                - Tempo evidence: %s
                - Trace/log availability: traceIdPresent=%s, logEvidenceCount=%d, traceEvidenceCount=%d

                Deterministic RCA:
                - Summary: %s
                - Confidence: %s
                - Evidence used: %s
                - Missing evidence: %s

                Rovo RCA Handoff:
                - Machine-readable block follows
                """.formatted(
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                replayCase.isSynthetic() ? "synthetic" : "real",
                repository.projectKey(),
                repository.repositorySlug(),
                repository.branch(),
                firstNonBlank(repository.commitSha(), jenkins.commitSha(), incidentVersion.commitSha()),
                jenkins.jobName(),
                jenkins.buildNumber(),
                jenkins.status(),
                jenkins.commitSha(),
                incidentVersion.version(),
                incidentVersion.commitSha(),
                incidentVersion.source(),
                incidentVersion.status(),
                observability.lokiEvidenceAvailable(),
                observability.tempoEvidenceAvailable(),
                observability.traceIdPresent(),
                observability.logEvidenceCount(),
                observability.traceEvidenceCount(),
                rca.summary(),
                rca.confidence(),
                rca.evidenceUsed(),
                rca.missingEvidence()
        );
    }

    private String rovoBlock(
            ReplayCaseEntity replayCase,
            GoldenPathEvidenceSnapshotResponse.Repository repository,
            GoldenPathEvidenceSnapshotResponse.Jenkins jenkins,
            GoldenPathEvidenceSnapshotResponse.IncidentVersion incidentVersion,
            GoldenPathEvidenceSnapshotResponse.Observability observability,
            GoldenPathEvidenceSnapshotResponse.DeterministicRca rca
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("jiraKey", replayCase.getJiraKey());
        root.put("targetKey", replayCase.getTargetKey());
        root.put("repository", repository.projectKey() + "/" + repository.repositorySlug());
        root.put("branch", repository.branch());
        root.put("commitSha", firstNonBlank(
                repository.commitSha(),
                jenkins.commitSha(),
                incidentVersion.commitSha()
        ));
        root.put("jenkinsJob", jenkins.jobName());
        root.put("jenkinsBuild", jenkins.buildNumber());
        root.put("incidentVersion", firstNonBlank(
                incidentVersion.version(),
                incidentVersion.commitSha()
        ));
        root.put("evidenceSummary", List.of(
                "lokiEvidenceAvailable=" + observability.lokiEvidenceAvailable(),
                "tempoEvidenceAvailable=" + observability.tempoEvidenceAvailable(),
                "traceIdPresent=" + observability.traceIdPresent()
        ));
        root.put("deterministicRca", Map.of(
                "summary", rca.summary(),
                "confidence", rca.confidence(),
                "evidenceUsed", rca.evidenceUsed(),
                "missingEvidence", rca.missingEvidence()
        ));
        root.put("requestedOutput", Map.of(
                "format", "REPLAYFIX_ROVO_RCA_RESULT_V1",
                "rules", List.of(
                        "Do not expose secrets",
                        "Do not claim confirmed root cause unless evidence supports it",
                        "Use HYPOTHESIS if reproduction is not proven",
                        "Return machine-readable JSON"
                )
        ));
        try {
            return "REPLAYFIX_ROVO_RCA_V1\n"
                    + objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root);
        } catch (Exception exception) {
            return "REPLAYFIX_ROVO_RCA_V1\n{}";
        }
    }

    private List<String> nextActions(
            String status,
            List<String> blockers,
            List<String> warnings
    ) {
        if ("BLOCKED".equals(status)) {
            return List.of(
                    "Fix source contract mismatches before Jira/Rovo handoff.",
                    "Regenerate AI input bundle from canonical evidence if needed."
            );
        }
        if (!warnings.isEmpty()) {
            return List.of(
                    "Review missing optional evidence before Rovo handoff.",
                    "Use the Jira markdown preview for human review only."
            );
        }
        return List.of(
                "Review Jira markdown preview.",
                "Use Rovo RCA input block for advisory handoff."
        );
    }

    private Optional<EvidenceEntity> latest(
            List<EvidenceEntity> evidence,
            EvidenceType type,
            String source
    ) {
        return evidence.stream()
                .filter(item -> item.getEvidenceType() == type)
                .filter(item -> source == null || source.equals(item.getSource()))
                .max(Comparator.comparing(EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Map<String, Object> map(EvidenceEntity evidence) {
        try {
            return objectMapper.readValue(
                    text(evidence),
                    new TypeReference<>() {
                    }
            );
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String textField(EvidenceEntity evidence, String... keys) {
        return string(map(evidence), keys);
    }

    private String string(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private List<String> stringList(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(sanitize(String.valueOf(item)));
                }
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    private String text(EvidenceEntity evidence) {
        if (evidence == null) {
            return "";
        }
        return firstNonBlank(evidence.getContentText(), evidence.getBody());
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        for (String marker : SENSITIVE_MARKERS) {
            sanitized = sanitized.replaceAll("(?i)" + marker, "[redacted]");
        }
        return sanitized;
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null
                        ? List.of()
                        : values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(this::sanitize)
                        .toList()
        ));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ContractResult(
            List<String> errors,
            List<String> warnings
    ) {
    }
}
