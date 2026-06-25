package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PatternInformedTestSourceService {

    private static final String PLAN_SOURCE = "regression-test-plan";
    private static final String PATTERN_SELECTION_SOURCE = "existing-test-pattern-selection";
    private static final String SOURCE_CONTEXT_SOURCE = "jenkins-validated-source-context";
    private static final String CANDIDATE_SOURCE = "pattern-informed-test-source-candidate";

    private final EvidenceService evidenceService;
    private final JavaSourceSignatureAnalyzer signatureAnalyzer;
    private final PatternInformedRegressionTestRenderer renderer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PatternInformedTestSourceService(
            EvidenceService evidenceService,
            JavaSourceSignatureAnalyzer signatureAnalyzer,
            PatternInformedRegressionTestRenderer renderer,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.signatureAnalyzer = signatureAnalyzer;
        this.renderer = renderer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PatternInformedTestSourceResult generate(UUID caseId) {
        EvidenceEntity planEvidence = latestRequired(
                caseId,
                EvidenceType.GENERATED_TEST,
                PLAN_SOURCE
        );

        RegressionTestPlan plan = parse(planEvidence, RegressionTestPlan.class);

        EvidenceEntity patternEvidence = latestRequired(
                caseId,
                EvidenceType.GENERATED_TEST,
                PATTERN_SELECTION_SOURCE
        );

        ExistingTestPatternSelection patternSelection = parse(
                patternEvidence,
                ExistingTestPatternSelection.class
        );

        EvidenceEntity sourceContextEvidence = latestRequired(
                caseId,
                EvidenceType.SOURCE_CONTEXT,
                SOURCE_CONTEXT_SOURCE
        );

        String sourceContextJson = sourceContextEvidence.getContentText();

        JavaSourceSignatureAnalysis sourceAnalysis = signatureAnalyzer.analyze(
                plan.targetProductionClass(),
                plan.targetProductionMethod(),
                sourceContextJson
        );

        PatternInformedTestSourceCandidate candidate = renderer.render(
                caseId,
                plan,
                patternSelection,
                sourceAnalysis
        );

        validateCandidate(candidate);

        saveEvidence(caseId, candidate);

        auditService.record(
                caseId,
                "PATTERN_INFORMED_TEST_SOURCE_CREATED",
                "system",
                "readiness="
                        + candidate.readiness()
                        + ", confidence="
                        + String.format("%.2f", candidate.compileConfidence())
                        + ", unresolvedSymbols="
                        + candidate.unresolvedSymbols().size()
        );

        return new PatternInformedTestSourceResult(
                caseId,
                "GENERATED_TEST",
                CANDIDATE_SOURCE,
                candidate
        );
    }

    private void validateCandidate(PatternInformedTestSourceCandidate candidate) {
        if (candidate.fileWritten()) {
            throw new IllegalStateException(
                    "Candidate must not have fileWritten=true at this stage."
            );
        }

        if (candidate.testExecuted()) {
            throw new IllegalStateException(
                    "Candidate must not have testExecuted=true at this stage."
            );
        }

        if (!candidate.humanApprovalRequired()) {
            throw new IllegalStateException(
                    "Candidate must require human approval."
            );
        }

        String path = candidate.proposedRelativePath();

        if (!path.startsWith("src/test/java")) {
            throw new IllegalStateException(
                    "Proposed path must start with src/test/java: " + path
            );
        }

        if (path.contains("../") || path.startsWith("/") || path.contains(":/")) {
            throw new IllegalStateException(
                    "Path contains traversal or absolute reference: " + path
            );
        }

        if (candidate.readiness() == TestSourceReadiness.READY_FOR_REVIEW
                && candidate.source().isBlank()) {
            throw new IllegalStateException(
                    "READY_FOR_REVIEW candidates must have non-empty source."
            );
        }

        if (candidate.readiness() == TestSourceReadiness.READY_FOR_REVIEW
                && !candidate.unresolvedSymbols().isEmpty()) {
            throw new IllegalStateException(
                    "READY_FOR_REVIEW candidates must have no unresolved symbols."
            );
        }

        if (candidate.compileConfidence() < 0.0 || candidate.compileConfidence() > 1.0) {
            throw new IllegalStateException(
                    "Compile confidence must be between 0 and 1: "
                            + candidate.compileConfidence()
            );
        }

        String expectedHash = calculateSha256(candidate.source());

        if (!expectedHash.equals(candidate.contentSha256())) {
            throw new IllegalStateException(
                    "Content SHA-256 does not match source."
            );
        }
    }

    private void saveEvidence(
            UUID caseId,
            PatternInformedTestSourceCandidate candidate
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.GENERATED_TEST,
                    CANDIDATE_SOURCE,
                    objectMapper.writeValueAsString(candidate),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save pattern-informed test source candidate evidence.",
                    exception
            );
        }
    }

    private EvidenceEntity latestRequired(
            UUID caseId,
            EvidenceType type,
            String source
    ) {
        return evidenceService.list(caseId)
                .stream()
                .filter(item -> item.getEvidenceType() == type)
                .filter(item -> source.equals(item.getSource()))
                .reduce((first, second) -> second)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Required evidence not found. type="
                                        + type
                                        + ", source="
                                        + source
                        )
                );
    }

    private <T> T parse(EvidenceEntity evidence, Class<T> type) {
        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    type
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse evidence. type="
                            + evidence.getEvidenceType()
                            + ", source="
                            + evidence.getSource(),
                    exception
            );
        }
    }

    private String calculateSha256(String value) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");

            return java.util.HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            )
                    );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot calculate SHA-256.",
                    exception
            );
        }
    }
}
