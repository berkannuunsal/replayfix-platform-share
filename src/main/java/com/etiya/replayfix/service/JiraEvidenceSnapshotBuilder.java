package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceAvailability;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.JiraEvidenceMatrixItem;
import com.etiya.replayfix.model.JiraEvidenceSnapshot;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JiraEvidenceSnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(JiraEvidenceSnapshotBuilder.class);

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final EvidenceSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    public JiraEvidenceSnapshotBuilder(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            EvidenceSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public JiraEvidenceSnapshot build(UUID caseId) {
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        List<EvidenceEntity> evidenceList = evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId);

        List<String> warnings = new ArrayList<>();
        List<JiraEvidenceMatrixItem> evidenceMatrix = new ArrayList<>();

        String businessImpact = null;
        String technicalSymptom = null;
        String affectedFlow = null;
        List<String> probableFailureChain = new ArrayList<>();
        String probableRootCause = null;
        double rootCauseConfidence = 0.0;
        List<String> competingHypotheses = new ArrayList<>();
        List<String> regressionTestHypothesis = new ArrayList<>();
        List<String> minimumFixDirection = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        String recommendedNextAction = null;

        for (EvidenceEntity evidence : evidenceList) {
            processEvidence(evidence, evidenceMatrix, warnings);
        }

        if (evidenceList.stream().noneMatch(item ->
                item.getEvidenceType() == EvidenceType.JIRA_ISSUE)) {
            missingEvidence.add("Jira issue details not collected");
        }

        businessImpact = "Evidence collected for "
                + caseEntity.getJiraKey()
                + " from ReplayFix case records.";
        technicalSymptom = buildTechnicalSymptom(evidenceList);
        affectedFlow = findFirstTextInEvidence(
                evidenceList,
                "targetFlow",
                "affectedFlow",
                "flow"
        ).orElse("See Jira issue summary");
        probableFailureChain = buildEvidenceChain(evidenceList);
        probableRootCause = findProbableRootCause(evidenceList)
                .orElse("Current evidence is available; review deterministic and Rovo RCA entries.");
        rootCauseConfidence = findRootCauseConfidence(evidenceList)
                .orElse(0.0);
        regressionTestHypothesis = buildRegressionHypothesis(evidenceList);
        minimumFixDirection = buildMinimumFixDirection(evidenceList);
        recommendedNextAction = "Review current evidence, Rovo RCA and regression hypothesis before approving next action.";

        String analysisId = "RF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String workflowStatus = "EVIDENCE_COLLECTED";
        String generatedAt = Instant.now().toString();

        return new JiraEvidenceSnapshot(
                caseId,
                caseEntity.getJiraKey(),
                analysisId,
                workflowStatus,
                generatedAt,
                businessImpact != null ? businessImpact : "Not determined",
                technicalSymptom != null ? technicalSymptom : "See evidence matrix",
                affectedFlow != null ? affectedFlow : "See Jira issue summary",
                evidenceMatrix,
                probableFailureChain.isEmpty() ? List.of("Evidence collection completed") : probableFailureChain,
                probableRootCause != null ? probableRootCause : "Deterministic analysis pending",
                rootCauseConfidence,
                competingHypotheses,
                regressionTestHypothesis,
                minimumFixDirection,
                missingEvidence,
                recommendedNextAction != null ? recommendedNextAction : "Review evidence matrix and approve Jira comment publication",
                warnings
        );
    }

    private void processEvidence(
            EvidenceEntity evidence,
            List<JiraEvidenceMatrixItem> evidenceMatrix,
            List<String> warnings
    ) {
        try {
            String source = determineSource(evidence);
            EvidenceAvailability status = determineAvailability(evidence);
            String keyFinding = extractKeyFinding(evidence);
            String confidence = determineConfidence(evidence);

            if (keyFinding == null || keyFinding.isBlank()) {
                return;
            }

            String sanitized = sanitizer.sanitize(keyFinding);
            if (sanitized == null || sanitized.isBlank()) {
                warnings.add("Evidence " + evidence.getId() + " sanitized to empty, excluded");
                return;
            }

            evidenceMatrix.add(new JiraEvidenceMatrixItem(
                    source,
                    status,
                    sanitized,
                    confidence,
                    evidence.getEvidenceType().name(),
                    evidence.getSource(),
                    evidence.getId().toString()
            ));

        } catch (Exception e) {
            log.warn("Failed to process evidence {}: {}", evidence.getId(), e.getMessage());
            warnings.add("Evidence processing error: " + evidence.getId());
        }
    }

    private String determineSource(EvidenceEntity evidence) {
        EvidenceType type = evidence.getEvidenceType();

        if (type == EvidenceType.JIRA_ISSUE) return "JIRA";
        if (type == EvidenceType.REPOSITORY_RESOLUTION
                || type == EvidenceType.INCIDENT_VERSION) return "BITBUCKET";
        if (type == EvidenceType.JENKINS_BUILD_CONTEXT) return "JENKINS";
        if (type == EvidenceType.LOKI_QUERY_PLAN
                || type == EvidenceType.LOKI_LOG
                || type == EvidenceType.LOKI_LOGS
                || type == EvidenceType.LOKI_CORRELATION_SIGNALS
                || type == EvidenceType.LOKI_SECOND_PASS
                || type == EvidenceType.INCIDENT_TIMELINE) return "LOKI";
        if (type == EvidenceType.TEMPO_ENRICHMENT) return "TEMPO";
        if (type == EvidenceType.SOURCE_CONTEXT) return "SOURCE_CONTEXT";
        if (type == EvidenceType.AI_INPUT_BUNDLE
                || type == EvidenceType.DETERMINISTIC_ROOT_CAUSE
                || type == EvidenceType.AI_ROOT_CAUSE
                || type == EvidenceType.ROVO_RCA
                || type == EvidenceType.REGRESSION_TEST_HYPOTHESIS
                || type == EvidenceType.FAILING_REGRESSION_TEST_DRAFT) return "REPLAYFIX";

        String source = evidence.getSource();
        return source == null ? "UNKNOWN" : source.toUpperCase();
    }

    private EvidenceAvailability determineAvailability(EvidenceEntity evidence) {
        EvidenceType type = evidence.getEvidenceType();

        if (type == EvidenceType.LOKI_QUERY_PLAN
                || type == EvidenceType.LOKI_LOG
                || type == EvidenceType.LOKI_LOGS
                || type == EvidenceType.LOKI_CORRELATION_SIGNALS
                || type == EvidenceType.LOKI_SECOND_PASS) {
            return hasZeroMetric(
                    evidence,
                    "matchedRowCount",
                    "matchedRows",
                    "matchedLogCount",
                    "logCount",
                    "resultCount"
            )
                    ? EvidenceAvailability.PROBABLE
                    : EvidenceAvailability.CONFIRMED;
        }

        if (type == EvidenceType.TEMPO_ENRICHMENT) {
            return hasZeroMetric(
                    evidence,
                    "foundTraceCount",
                    "traceCount",
                    "foundTraces"
            )
                    ? EvidenceAvailability.PROBABLE
                    : EvidenceAvailability.CONFIRMED;
        }

        if (type == EvidenceType.SOURCE_CONTEXT) {
            return hasZeroMetric(
                    evidence,
                    "matchedFileCount",
                    "scannedFileCount",
                    "fileCount"
            )
                    ? EvidenceAvailability.PROBABLE
                    : EvidenceAvailability.CONFIRMED;
        }

        return EvidenceAvailability.CONFIRMED;
    }

    private String extractKeyFinding(EvidenceEntity evidence) {
        try {
            EvidenceType type = evidence.getEvidenceType();
            Optional<JsonNode> json = readEvidenceJson(evidence);

            if (type == EvidenceType.REPOSITORY_RESOLUTION) {
                return "Repository: "
                        + findText(json, "repository", "repositoryFullName", "repo", "project")
                        .orElse("resolved")
                        + ", branch: "
                        + findText(json, "branch", "targetBranch")
                        .orElse("resolved");
            }

            if (type == EvidenceType.INCIDENT_VERSION) {
                return "Incident version validation available"
                        + findText(json, "commitSha", "jenkinsCommitSha", "checkoutCommitSha")
                        .map(value -> ": " + value)
                        .orElse("");
            }

            if (type == EvidenceType.JENKINS_BUILD_CONTEXT) {
                Optional<String> job = findText(json, "jobName", "job");
                Optional<String> build = findText(json, "buildNumber", "build");
                if (job.isPresent() || build.isPresent()) {
                    return "Jenkins build: "
                            + job.orElse("resolved")
                            + (build.isPresent() ? " #" + build.get() : "");
                }
                return "Jenkins build context available";
            }

            if (type == EvidenceType.TEMPO_ENRICHMENT) {
                return "Tempo enrichment collected"
                        + findIntField(json.orElse(null), "foundTraceCount")
                        .map(value -> ": foundTraceCount=" + value)
                        .orElse("");
            }

            if (type == EvidenceType.SOURCE_CONTEXT) {
                return "Source context collected"
                        + findIntField(json.orElse(null), "matchedFileCount")
                        .map(value -> ": matchedFileCount=" + value)
                        .orElse("");
            }

            if (type == EvidenceType.DETERMINISTIC_ROOT_CAUSE) {
                return findText(json, "probableRootCause", "rootCause", "summary")
                        .map(value -> "Deterministic RCA: " + value)
                        .orElse("Deterministic RCA evidence available");
            }

            if (type == EvidenceType.ROVO_RCA) {
                return findText(json, "probableRootCause")
                        .map(value -> "Rovo RCA imported: " + value)
                        .orElse("Rovo RCA imported");
            }

            if (type == EvidenceType.REGRESSION_TEST_HYPOTHESIS) {
                return findText(json, "targetFlow", "failingScenario")
                        .map(value -> "Regression test hypothesis: " + value)
                        .orElse("Regression test hypothesis generated");
            }

            if (type == EvidenceType.FAILING_REGRESSION_TEST_DRAFT) {
                return findText(json, "proposedRelativePath", "proposedClassName")
                        .map(value -> "Failing regression test draft: " + value)
                        .orElse("Failing regression test draft generated");
            }

            String body = firstNonBlank(evidence.getContentText(), evidence.getBody());
            if (body == null) return null;

            JsonNode node = objectMapper.readTree(body);

            if (node.has("summary")) {
                return node.get("summary").asText();
            }

            if (node.has("keyFinding")) {
                return node.get("keyFinding").asText();
            }

            if (node.has("finding")) {
                return node.get("finding").asText();
            }

            if (body.length() > 500) {
                return body.substring(0, 500) + "...";
            }

            return body;

        } catch (Exception e) {
            String body = firstNonBlank(evidence.getContentText(), evidence.getBody());
            if (body != null && body.length() > 500) {
                return body.substring(0, 500) + "...";
            }
            return body;
        }
    }

    private String determineConfidence(EvidenceEntity evidence) {
        if (evidence.getConfidence() != null) {
            double conf = evidence.getConfidence();
            if (conf >= 0.9) return "High";
            if (conf >= 0.7) return "Medium";
            return "Low";
        }
        return "N/A";
    }

    private String buildTechnicalSymptom(List<EvidenceEntity> evidenceList) {
        List<String> parts = new ArrayList<>();

        findEvidence(evidenceList, EvidenceType.REPOSITORY_RESOLUTION)
                .flatMap(item -> findText(
                        readEvidenceJson(item),
                        "repository",
                        "repositoryFullName",
                        "repo"
                ))
                .ifPresent(value -> parts.add("Repository: " + value));

        findEvidence(evidenceList, EvidenceType.REPOSITORY_RESOLUTION)
                .flatMap(item -> findText(
                        readEvidenceJson(item),
                        "branch",
                        "targetBranch"
                ))
                .ifPresent(value -> parts.add("Branch: " + value));

        findEvidence(evidenceList, EvidenceType.JENKINS_BUILD_CONTEXT)
                .ifPresent(item -> parts.add(extractKeyFinding(item)));

        findEvidence(evidenceList, EvidenceType.INCIDENT_VERSION)
                .ifPresent(item -> parts.add("Incident version validated"));

        return parts.isEmpty()
                ? "See evidence matrix"
                : String.join("; ", parts);
    }

    private List<String> buildEvidenceChain(List<EvidenceEntity> evidenceList) {
        List<String> chain = new ArrayList<>();

        findEvidence(evidenceList, EvidenceType.REPOSITORY_RESOLUTION)
                .ifPresent(item -> chain.add(extractKeyFinding(item)));
        findEvidence(evidenceList, EvidenceType.JENKINS_BUILD_CONTEXT)
                .ifPresent(item -> chain.add(extractKeyFinding(item)));
        findEvidence(evidenceList, EvidenceType.INCIDENT_VERSION)
                .ifPresent(item -> chain.add(extractKeyFinding(item)));
        findEvidence(evidenceList, EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .ifPresent(item -> chain.add("Deterministic RCA status: available"));
        findEvidence(evidenceList, EvidenceType.ROVO_RCA)
                .ifPresent(item -> chain.add("Rovo RCA imported"));
        findEvidence(evidenceList, EvidenceType.REGRESSION_TEST_HYPOTHESIS)
                .ifPresent(item -> chain.add("Regression test hypothesis generated"));

        return chain.stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    private Optional<String> findProbableRootCause(List<EvidenceEntity> evidenceList) {
        return latestEvidence(evidenceList, EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .flatMap(item -> findText(
                        readEvidenceJson(item),
                        "probableRootCause",
                        "rootCause",
                        "summary"
                ))
                .or(() -> latestEvidence(evidenceList, EvidenceType.ROVO_RCA)
                        .flatMap(item -> findText(
                                readEvidenceJson(item),
                                "probableRootCause",
                                "rawHumanReport"
                        )));
    }

    private Optional<Double> findRootCauseConfidence(List<EvidenceEntity> evidenceList) {
        return latestEvidence(evidenceList, EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .map(EvidenceEntity::getConfidence)
                .or(() -> latestEvidence(evidenceList, EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                        .flatMap(item -> findDouble(
                                readEvidenceJson(item),
                                "confidence"
                        )))
                .or(() -> latestEvidence(evidenceList, EvidenceType.ROVO_RCA)
                        .map(EvidenceEntity::getConfidence))
                .or(() -> latestEvidence(evidenceList, EvidenceType.ROVO_RCA)
                        .flatMap(item -> findDouble(
                                readEvidenceJson(item),
                                "confidence"
                        )));
    }

    private List<String> buildRegressionHypothesis(List<EvidenceEntity> evidenceList) {
        return latestEvidence(evidenceList, EvidenceType.REGRESSION_TEST_HYPOTHESIS)
                .flatMap(item -> findText(
                        readEvidenceJson(item),
                        "failingScenario",
                        "targetFlow",
                        "testType"
                ))
                .map(List::of)
                .orElseGet(List::of);
    }

    private List<String> buildMinimumFixDirection(List<EvidenceEntity> evidenceList) {
        return latestEvidence(evidenceList, EvidenceType.ROVO_RCA)
                .flatMap(item -> findText(
                        readEvidenceJson(item),
                        "minimumFixDirection",
                        "recommendedNextAction"
                ))
                .map(List::of)
                .orElseGet(List::of);
    }

    private Optional<EvidenceEntity> findEvidence(
            List<EvidenceEntity> evidenceList,
            EvidenceType type
    ) {
        return evidenceList.stream()
                .filter(item -> item.getEvidenceType() == type)
                .findFirst();
    }

    private Optional<EvidenceEntity> latestEvidence(
            List<EvidenceEntity> evidenceList,
            EvidenceType type
    ) {
        return evidenceList.stream()
                .filter(item -> item.getEvidenceType() == type)
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private Optional<String> findFirstTextInEvidence(
            List<EvidenceEntity> evidenceList,
            String... fieldNames
    ) {
        return evidenceList.stream()
                .map(this::readEvidenceJson)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(node -> findText(Optional.of(node), fieldNames))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private Optional<JsonNode> readEvidenceJson(EvidenceEntity evidence) {
        String content = firstNonBlank(
                evidence.getContentText(),
                evidence.getBody()
        );

        if (content == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> findText(Optional<JsonNode> node, String... fieldNames) {
        if (node.isEmpty()) {
            return Optional.empty();
        }

        for (String fieldName : fieldNames) {
            Optional<String> value = findTextField(node.get(), fieldName);
            if (value.isPresent()) {
                return value;
            }
        }

        return Optional.empty();
    }

    private Optional<Double> findDouble(Optional<JsonNode> node, String... fieldNames) {
        if (node.isEmpty()) {
            return Optional.empty();
        }

        for (String fieldName : fieldNames) {
            Optional<Double> value = findDoubleField(node.get(), fieldName);
            if (value.isPresent()) {
                return value;
            }
        }

        return Optional.empty();
    }

    private Optional<Double> findDoubleField(JsonNode node, String fieldName) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                return Optional.of(value.asDouble());
            }

            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                Optional<Double> childValue =
                        findDoubleField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<Double> childValue =
                        findDoubleField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> findTextField(JsonNode node, String fieldName) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null) {
                if (value.isTextual()) {
                    return Optional.of(value.asText());
                }
                if (value.isNumber() || value.isBoolean()) {
                    return Optional.of(value.asText());
                }
                if (value.isArray() && !value.isEmpty()) {
                    JsonNode first = value.get(0);
                    if (first.isTextual()) {
                        return Optional.of(first.asText());
                    }
                    if (first.isObject()) {
                        return findTextField(first, "statement")
                                .or(() -> findTextField(first, "reason"))
                                .or(() -> findTextField(first, "title"));
                    }
                }
            }

            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                Optional<String> childValue =
                        findTextField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> childValue =
                        findTextField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private boolean hasZeroMetric(EvidenceEntity evidence, String... fieldNames) {
        Optional<JsonNode> json = readEvidenceJson(evidence);

        if (json.isEmpty()) {
            return false;
        }

        for (String fieldName : fieldNames) {
            Optional<Integer> value = findIntField(json.get(), fieldName);
            if (value.isPresent()) {
                return value.get() == 0;
            }
        }

        return false;
    }

    private Optional<Integer> findIntField(JsonNode node, String fieldName) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                return Optional.of(value.asInt());
            }

            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                Optional<Integer> childValue =
                        findIntField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<Integer> childValue =
                        findIntField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
