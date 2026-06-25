package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.RegressionTestHypothesis;
import com.etiya.replaylab.model.RegressionTestHypothesisResult;
import com.etiya.replaylab.model.RovoRcaAnalysis;
import com.etiya.replaylab.model.RovoRcaEnvelope;
import com.etiya.replaylab.repository.EvidenceRepository;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class RegressionTestHypothesisService {

    private static final String SOURCE = "regression-test-hypothesis";

    private final EvidenceRepository evidenceRepository;
    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public RegressionTestHypothesisService(
            EvidenceRepository evidenceRepository,
            EvidenceService evidenceService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.evidenceRepository = evidenceRepository;
        this.evidenceService = evidenceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public RegressionTestHypothesisResult generate(UUID caseId, boolean force) {
        EvidenceEntity rovoEvidence = latestRequired(caseId, EvidenceType.ROVO_RCA);

        Optional<EvidenceEntity> existing = latest(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS);
        if (existing.isPresent() && !force) {
            RegressionTestHypothesis hypothesis = parseHypothesis(existing.get());
            return new RegressionTestHypothesisResult(
                    caseId,
                    EvidenceType.REGRESSION_TEST_HYPOTHESIS.name(),
                    SOURCE,
                    false,
                    null,
                    existing.get().getId(),
                    hypothesis,
                    List.of("Regression test hypothesis already exists. Use force=true to regenerate.")
            );
        }

        RovoRcaEnvelope envelope = readEnvelope(rovoEvidence);
        RovoRcaAnalysis analysis = readAnalysis(envelope);
        RegressionTestHypothesis hypothesis = build(caseId, rovoEvidence, envelope, analysis);

        EvidenceEntity saved = existing
                .map(item -> replace(item, hypothesis))
                .orElseGet(() -> save(caseId, hypothesis));

        auditService.record(
                caseId,
                "REGRESSION_TEST_HYPOTHESIS_CREATED",
                "replaylab-platform",
                "Regression test hypothesis created from ROVO_RCA evidence. evidenceId="
                        + saved.getId()
                        + ", rovoEvidenceId="
                        + rovoEvidence.getId()
                        + ", testType="
                        + hypothesis.testType()
        );

        return new RegressionTestHypothesisResult(
                caseId,
                EvidenceType.REGRESSION_TEST_HYPOTHESIS.name(),
                SOURCE,
                true,
                saved.getId(),
                null,
                hypothesis,
                hypothesis.warnings()
        );
    }

    private RegressionTestHypothesis build(
            UUID caseId,
            EvidenceEntity rovoEvidence,
            RovoRcaEnvelope envelope,
            RovoRcaAnalysis analysis
    ) {
        List<String> warnings = new ArrayList<>();

        String probableRootCause = defaultText(
                analysis.probableRootCause(),
                "Rovo RCA did not provide a probable root cause."
        );

        String targetFlow = firstNonBlank(
                analysis.affectedFlow(),
                analysis.technicalSymptom(),
                analysis.executiveSummary(),
                probableRootCause
        );

        String testType = inferTestType(analysis, probableRootCause + " " + targetFlow);

        List<String> missingEvidence = analysis.missingEvidence() == null
                ? List.of()
                : analysis.missingEvidence();

        if (!missingEvidence.isEmpty()) {
            warnings.add("Rovo RCA reports missing evidence; generated hypothesis requires human validation.");
        }

        List<String> rovoWarnings = analysis.warnings() == null
                ? List.of()
                : analysis.warnings();
        warnings.addAll(rovoWarnings);

        if (analysis.confidence() == null || analysis.confidence() < 0.5) {
            warnings.add("Rovo confidence is low; keep this as a hypothesis, not an implementation instruction.");
        }

        List<String> sourceEvidence = new ArrayList<>();
        sourceEvidence.add("ROVO_RCA:" + rovoEvidence.getId());
        sourceEvidence.add("ROVO_RCA_COMMENT:" + nullToUnknown(envelope.commentId()));
        sourceEvidence.add("ROVO_RCA_STATUS:" + defaultText(analysis.status(), "HYPOTHESIS"));

        return new RegressionTestHypothesis(
                RegressionTestHypothesis.SCHEMA_VERSION,
                caseId,
                envelope.jiraKey(),
                SOURCE,
                Instant.now(),
                "HYPOTHESIS",
                testType,
                truncate(targetFlow, 500),
                defaultText(analysis.impactedComponent(), "UNKNOWN"),
                probableRootCause,
                analysis.confidence() == null ? 0.0 : analysis.confidence(),
                buildFailingScenario(analysis, probableRootCause, targetFlow),
                buildPreconditions(analysis),
                buildSuggestedInputs(analysis),
                buildExpectedFailureSignals(analysis, probableRootCause),
                buildAssertions(analysis, probableRootCause),
                buildMocksOrDependencies(analysis),
                missingEvidence,
                sourceEvidence,
                buildRovoSummary(envelope, analysis),
                false,
                false,
                false,
                false,
                true,
                warnings
        );
    }

    private String inferTestType(RovoRcaAnalysis analysis, String text) {
        String normalized = normalize(text);
        if (normalized.contains("endpoint")
                || normalized.contains("api")
                || normalized.contains("http")
                || normalized.contains("request")) {
            return "API_REGRESSION";
        }
        if (normalized.contains("database")
                || normalized.contains("integration")
                || normalized.contains("kafka")
                || normalized.contains("jenkins")
                || normalized.contains("loki")
                || normalized.contains("tempo")) {
            return "INTEGRATION_REGRESSION";
        }
        if (analysis.suspectedMethods() != null && !analysis.suspectedMethods().isEmpty()) {
            return "UNIT_REGRESSION";
        }
        return "REGRESSION_HYPOTHESIS";
    }

    private String buildFailingScenario(RovoRcaAnalysis analysis, String probableRootCause, String targetFlow) {
        return truncate(
                "Reproduce the Jira-described defect for "
                        + defaultText(targetFlow, "the affected flow")
                        + ". Before the fix, the test should expose: "
                        + probableRootCause,
                1000
        );
    }

    private List<String> buildPreconditions(RovoRcaAnalysis analysis) {
        List<String> values = new ArrayList<>();
        values.add("Use the ReplayLab incident version verified for this case.");
        values.add("Use sanitized Jira/Rovo RCA evidence only; do not replay production secrets.");

        if (analysis.impactedComponent() != null && !analysis.impactedComponent().isBlank()) {
            values.add("Focus on impacted component: " + analysis.impactedComponent());
        }

        if (analysis.suspectedFiles() != null) {
            analysis.suspectedFiles().stream()
                    .map(RovoRcaAnalysis.SuspectedFile::path)
                    .filter(path -> path != null && !path.isBlank())
                    .limit(3)
                    .map(path -> "Review suspected source file before writing a test: " + path)
                    .forEach(values::add);
        }

        return values;
    }

    private List<String> buildSuggestedInputs(RovoRcaAnalysis analysis) {
        List<String> values = new ArrayList<>();
        if (analysis.regressionTestHypothesis() != null && !analysis.regressionTestHypothesis().isEmpty()) {
            values.addAll(analysis.regressionTestHypothesis());
        }
        if (values.isEmpty()) {
            values.add("Build the minimum sanitized request/domain object that exercises the affected flow.");
        }
        return values;
    }

    private List<String> buildExpectedFailureSignals(RovoRcaAnalysis analysis, String probableRootCause) {
        List<String> values = new ArrayList<>();
        if (analysis.technicalSymptom() != null && !analysis.technicalSymptom().isBlank()) {
            values.add(analysis.technicalSymptom());
        }
        values.add("The incident version should fail or expose the behavior described by Rovo RCA: " + probableRootCause);
        return values;
    }

    private List<String> buildAssertions(RovoRcaAnalysis analysis, String probableRootCause) {
        List<String> values = new ArrayList<>();
        values.add("Assert the incident behavior before the fix using the expected failure signal.");
        values.add("Assert the corrected business behavior after a human-approved fix.");

        if (analysis.minimumFixDirection() != null && !analysis.minimumFixDirection().isEmpty()) {
            values.add("Assert that the minimum fix direction is covered: " + analysis.minimumFixDirection().get(0));
        }

        if (normalize(probableRootCause).contains("validation")) {
            values.add("Assert invalid or missing input is rejected deterministically with a safe response.");
        }

        return values;
    }

    private List<String> buildMocksOrDependencies(RovoRcaAnalysis analysis) {
        List<String> values = new ArrayList<>();
        if (analysis.confluenceReferences() != null && !analysis.confluenceReferences().isEmpty()) {
            values.add("Use Confluence/EtiyaWiki references only as context; do not encode undocumented assumptions.");
        }
        if (analysis.evidenceMatrix() != null) {
            analysis.evidenceMatrix().stream()
                    .filter(entry -> entry.category() != null && !entry.category().isBlank())
                    .limit(5)
                    .map(entry -> "Evidence dependency: " + entry.category() + " status=" + nullToUnknown(entry.status()))
                    .forEach(values::add);
        }
        if (values.isEmpty()) {
            values.add("Mock only external systems required to reproduce the failing path.");
        }
        return values;
    }

    private Map<String, Object> buildRovoSummary(RovoRcaEnvelope envelope, RovoRcaAnalysis analysis) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("commentId", envelope.commentId());
        summary.put("commentAuthor", envelope.commentAuthor());
        summary.put("rcaStatus", defaultText(analysis.status(), "HYPOTHESIS"));
        summary.put("confidence", analysis.confidence() == null ? 0.0 : analysis.confidence());
        summary.put("probableRootCause", analysis.probableRootCause());
        summary.put("normalizationWarnings", envelope.normalizationWarnings());
        return summary;
    }

    private EvidenceEntity save(UUID caseId, RegressionTestHypothesis hypothesis) {
        try {
            return evidenceService.save(
                    caseId,
                    EvidenceType.REGRESSION_TEST_HYPOTHESIS,
                    SOURCE,
                    objectMapper.writeValueAsString(hypothesis),
                    true
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot save regression test hypothesis.", exception);
        }
    }

    private EvidenceEntity replace(EvidenceEntity existing, RegressionTestHypothesis hypothesis) {
        try {
            existing.setSource(SOURCE);
            String sanitized = evidenceService.sanitize(objectMapper.writeValueAsString(hypothesis));
            existing.setContentText(sanitized);
            existing.setContentHash(evidenceService.hashContent(sanitized));
            existing.setSanitized(true);
            return evidenceRepository.save(existing);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot replace regression test hypothesis.", exception);
        }
    }

    private RovoRcaEnvelope readEnvelope(EvidenceEntity evidence) {
        try {
            String content = evidence.getContentText();
            try {
                return objectMapper.readValue(content, RovoRcaEnvelope.class);
            } catch (Exception legacyException) {
                JsonNode legacy = objectMapper.readTree(content);
                return new RovoRcaEnvelope(
                        RovoRcaEnvelope.ENVELOPE_SCHEMA_VERSION,
                        evidence.getCaseId(),
                        legacy.path("jiraKey").asText(null),
                        "rovo-incident-commander",
                        null,
                        null,
                        evidence.getCreatedAt(),
                        "IMPORTED",
                        legacy.path("status").asText("HYPOTHESIS"),
                        "",
                        legacy,
                        legacy,
                        List.of("Legacy JSON-only ROVO_RCA evidence was used.")
                );
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse ROVO_RCA evidence.", exception);
        }
    }

    private RovoRcaAnalysis readAnalysis(RovoRcaEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.normalizedRovoJson(), RovoRcaAnalysis.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse normalized Rovo RCA JSON.", exception);
        }
    }

    private RegressionTestHypothesis parseHypothesis(EvidenceEntity evidence) {
        try {
            return objectMapper.readValue(evidence.getContentText(), RegressionTestHypothesis.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse existing regression test hypothesis.", exception);
        }
    }

    private EvidenceEntity latestRequired(UUID caseId, EvidenceType type) {
        return latest(caseId, type)
                .orElseThrow(() -> new IllegalStateException("Required evidence not found. type=" + type));
    }

    private Optional<EvidenceEntity> latest(UUID caseId, EvidenceType type) {
        return evidenceRepository.findByCaseIdAndEvidenceType(caseId, type)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
