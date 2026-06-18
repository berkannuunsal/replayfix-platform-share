package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.FailingRegressionTestDraft;
import com.etiya.replayfix.model.FailingRegressionTestDraftResult;
import com.etiya.replayfix.model.RegressionTestHypothesis;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class FailingRegressionTestDraftService {

    private static final String SOURCE = "failing-regression-test-draft";

    private final EvidenceRepository evidenceRepository;
    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public FailingRegressionTestDraftService(
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

    public FailingRegressionTestDraftResult generate(UUID caseId, boolean force) {
        EvidenceEntity hypothesisEvidence = latestRequired(
                caseId,
                EvidenceType.REGRESSION_TEST_HYPOTHESIS
        );

        Optional<EvidenceEntity> existing = latest(
                caseId,
                EvidenceType.FAILING_REGRESSION_TEST_DRAFT
        );

        if (existing.isPresent() && !force) {
            FailingRegressionTestDraft draft = parseDraft(existing.get());
            return new FailingRegressionTestDraftResult(
                    caseId,
                    EvidenceType.FAILING_REGRESSION_TEST_DRAFT.name(),
                    SOURCE,
                    false,
                    null,
                    existing.get().getId(),
                    draft,
                    List.of("Failing regression test draft already exists. Use force=true to regenerate.")
            );
        }

        RegressionTestHypothesis hypothesis = parseHypothesis(hypothesisEvidence);
        FailingRegressionTestDraft draft = build(caseId, hypothesisEvidence.getId(), hypothesis);

        EvidenceEntity saved = existing
                .map(item -> replace(item, draft))
                .orElseGet(() -> save(caseId, draft));

        auditService.record(
                caseId,
                "FAILING_REGRESSION_TEST_DRAFT_CREATED",
                "replayfix-platform",
                "Failing regression test draft created from hypothesis. evidenceId="
                        + saved.getId()
                        + ", hypothesisEvidenceId="
                        + hypothesisEvidence.getId()
                        + ", path="
                        + draft.proposedRelativePath()
        );

        return new FailingRegressionTestDraftResult(
                caseId,
                EvidenceType.FAILING_REGRESSION_TEST_DRAFT.name(),
                SOURCE,
                true,
                saved.getId(),
                null,
                draft,
                draft.warnings()
        );
    }

    private FailingRegressionTestDraft build(
            UUID caseId,
            UUID hypothesisEvidenceId,
            RegressionTestHypothesis hypothesis
    ) {
        String className = proposedClassName(hypothesis);
        String methodName = proposedMethodName(hypothesis);
        String packageName = "com.etiya.replayfix.generated";
        String relativePath = "src/test/java/"
                + packageName.replace('.', '/')
                + "/"
                + className
                + ".java";

        List<String> warnings = new ArrayList<>();
        warnings.addAll(hypothesis.warnings() == null ? List.of() : hypothesis.warnings());
        warnings.add("Draft source was not written to a repository workspace.");
        warnings.add("Draft test was not executed.");
        warnings.add("Generated source is disabled and requires human completion before execution.");

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Project-specific controller/client/service collaborators must be selected by a human.");
        assumptions.add("Production request data must remain sanitized before it is copied into a test fixture.");
        if (hypothesis.missingEvidence() != null && !hypothesis.missingEvidence().isEmpty()) {
            assumptions.add("Missing evidence must be resolved or accepted before treating this as a final failing test.");
        }

        String sourceCode = renderSource(
                packageName,
                className,
                methodName,
                hypothesis
        );

        String sha256 = sha256(sourceCode);

        List<String> sourceEvidence = new ArrayList<>();
        sourceEvidence.add("REGRESSION_TEST_HYPOTHESIS:" + hypothesisEvidenceId);
        if (hypothesis.sourceEvidence() != null) {
            sourceEvidence.addAll(hypothesis.sourceEvidence());
        }

        return new FailingRegressionTestDraft(
                FailingRegressionTestDraft.SCHEMA_VERSION,
                caseId,
                hypothesisEvidenceId,
                hypothesis.jiraKey(),
                SOURCE,
                Instant.now(),
                "DRAFT",
                "NEEDS_HUMAN_COMPLETION",
                hypothesis.testType(),
                relativePath,
                packageName,
                className,
                methodName,
                "JAVA",
                "JUnit 5",
                sourceCode,
                sha256,
                hypothesis.targetFlow(),
                hypothesis.probableRootCause(),
                hypothesis.expectedFailureSignals() == null ? List.of() : hypothesis.expectedFailureSignals(),
                hypothesis.assertions() == null ? List.of() : hypothesis.assertions(),
                sourceEvidence,
                assumptions,
                warnings,
                false,
                false,
                false,
                false,
                true
        );
    }

    private String renderSource(
            String packageName,
            String className,
            String methodName,
            RegressionTestHypothesis hypothesis
    ) {
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n");
        source.append("import org.junit.jupiter.api.Disabled;\n");
        source.append("import org.junit.jupiter.api.DisplayName;\n");
        source.append("import org.junit.jupiter.api.Test;\n\n");
        source.append("import static org.junit.jupiter.api.Assertions.fail;\n\n");
        source.append("class ").append(className).append(" {\n\n");
        source.append("    @Test\n");
        source.append("    @Disabled(\"ReplayFix draft: requires human completion before execution\")\n");
        source.append("    @DisplayName(\"")
                .append(escapeJava(truncate(hypothesis.targetFlow(), 140)))
                .append("\")\n");
        source.append("    void ").append(methodName).append("() {\n");
        appendComments(source, "Target flow", List.of(hypothesis.targetFlow()));
        appendComments(source, "Probable root cause", List.of(hypothesis.probableRootCause()));
        appendComments(source, "Preconditions", hypothesis.preconditions());
        appendComments(source, "Suggested inputs", hypothesis.suggestedInputs());
        appendComments(source, "Expected failure signals", hypothesis.expectedFailureSignals());
        appendComments(source, "Assertions", hypothesis.assertions());
        appendComments(source, "Mocks or dependencies", hypothesis.mocksOrDependencies());
        appendComments(source, "Missing evidence", hypothesis.missingEvidence());
        source.append("        fail(\"Implement sanitized arrange/act/assert steps to reproduce the incident before enabling this test.\");\n");
        source.append("    }\n");
        source.append("}\n");
        return source.toString();
    }

    private void appendComments(StringBuilder source, String title, List<String> values) {
        source.append("        // ").append(title).append(":\n");
        if (values == null || values.isEmpty()) {
            source.append("        // - Not provided.\n\n");
            return;
        }
        for (String value : values) {
            source.append("        // - ")
                    .append(safeComment(value))
                    .append("\n");
        }
        source.append("\n");
    }

    private EvidenceEntity save(UUID caseId, FailingRegressionTestDraft draft) {
        try {
            return evidenceService.save(
                    caseId,
                    EvidenceType.FAILING_REGRESSION_TEST_DRAFT,
                    SOURCE,
                    objectMapper.writeValueAsString(draft),
                    true
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot save failing regression test draft.", exception);
        }
    }

    private EvidenceEntity replace(EvidenceEntity existing, FailingRegressionTestDraft draft) {
        try {
            String sanitized = evidenceService.sanitize(objectMapper.writeValueAsString(draft));
            existing.setSource(SOURCE);
            existing.setContentText(sanitized);
            existing.setContentHash(evidenceService.hashContent(sanitized));
            existing.setSanitized(true);
            return evidenceRepository.save(existing);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot replace failing regression test draft.", exception);
        }
    }

    private RegressionTestHypothesis parseHypothesis(EvidenceEntity evidence) {
        try {
            return objectMapper.readValue(evidence.getContentText(), RegressionTestHypothesis.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse regression test hypothesis.", exception);
        }
    }

    private FailingRegressionTestDraft parseDraft(EvidenceEntity evidence) {
        try {
            return objectMapper.readValue(evidence.getContentText(), FailingRegressionTestDraft.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse failing regression test draft.", exception);
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

    private String proposedClassName(RegressionTestHypothesis hypothesis) {
        String token = sanitizeJavaIdentifier(toPascalCase(hypothesis.jiraKey()));
        String flow = sanitizeJavaIdentifier(toPascalCase(hypothesis.targetFlow()));
        String base = flow.isBlank() ? token : flow;
        if (base.isBlank()) {
            base = "ReplayFixIncident";
        }
        return truncateIdentifier(base, 70) + "FailingRegressionTest";
    }

    private String proposedMethodName(RegressionTestHypothesis hypothesis) {
        String token = sanitizeJavaIdentifier(toPascalCase(firstNonBlank(
                hypothesis.testType(),
                hypothesis.targetFlow(),
                "incident"
        )));
        if (token.isBlank()) {
            token = "Incident";
        }
        return "shouldReproduce" + truncateIdentifier(token, 70);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String toPascalCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] parts = value.replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            result.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                result.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    private String sanitizeJavaIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String candidate = value.replaceAll("[^A-Za-z0-9_$]", "");
        if (candidate.isBlank()) {
            return "";
        }
        if (!Character.isJavaIdentifierStart(candidate.charAt(0))) {
            candidate = "Incident" + candidate;
        }
        return candidate;
    }

    private String truncateIdentifier(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String safeComment(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("*/", "* /")
                .trim();
    }

    private String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot calculate SHA-256.", exception);
        }
    }
}
