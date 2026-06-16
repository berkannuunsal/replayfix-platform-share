package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ApprovalRequestEntity;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovedPatternInformedTestWriteService {

    private static final String CANDIDATE_SOURCE =
            "pattern-informed-test-source-candidate";

    private static final String OUTPUT_SOURCE =
            "approved-pattern-informed-test-write-result";

    private static final String PRE_WRITE_SOURCE =
            "approved-pattern-informed-test-source";

    private final ReplayFixProperties properties;
    private final EvidenceService evidenceService;
    private final ApprovalService approvalService;
    private final VersionedTestPathResolver pathResolver;
    private final JavaTopLevelClassRenamer classRenamer;
    private final AtomicWorkspaceFileWriter fileWriter;
    private final GitWorkspaceService gitService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ApprovedPatternInformedTestWriteService(
            ReplayFixProperties properties,
            EvidenceService evidenceService,
            ApprovalService approvalService,
            VersionedTestPathResolver pathResolver,
            JavaTopLevelClassRenamer classRenamer,
            AtomicWorkspaceFileWriter fileWriter,
            GitWorkspaceService gitService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceService = evidenceService;
        this.approvalService = approvalService;
        this.pathResolver = pathResolver;
        this.classRenamer = classRenamer;
        this.fileWriter = fileWriter;
        this.gitService = gitService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PatternInformedTestWriteResult write(
            UUID caseId,
            UUID approvalId
    ) {
        if (!properties.getPolicy().isAllowGeneratedCodeWrite()) {
            throw new IllegalStateException(
                    "Generated code write is disabled by policy."
            );
        }

        EvidenceEntity candidateEvidence = latestRequired(
                caseId,
                EvidenceType.GENERATED_TEST,
                CANDIDATE_SOURCE
        );

        PatternInformedTestSourceCandidate candidate = parse(
                candidateEvidence,
                PatternInformedTestSourceCandidate.class
        );

        validateCandidate(candidate);

        ApprovalRequestEntity approval =
                approvalService.requireApprovedPatternInformedTestSource(
                        caseId,
                        candidateEvidence.getId()
                );

        if (!approval.getId().equals(approvalId)) {
            throw new IllegalStateException(
                    "Approval ID mismatch. Expected: "
                            + approval.getId()
                            + ", provided: "
                            + approvalId
            );
        }

        String actualHash = calculateSha256(candidate.source());

        if (!actualHash.equalsIgnoreCase(candidate.contentSha256())) {
            throw new IllegalStateException(
                    "Pattern-informed candidate hash mismatch."
            );
        }

        EvidenceEntity checkoutEvidence = latestRequired(
                caseId,
                EvidenceType.SOURCE_CHECKOUT,
                "source-checkout-metadata"
        );

        SourceCheckoutResult checkout = parse(
                checkoutEvidence,
                SourceCheckoutResult.class
        );

        Path workspace = Path.of(checkout.workspace())
                .toAbsolutePath()
                .normalize();

        VersionedTestPathResolver.VersionedPath versionedPath =
                pathResolver.resolve(
                        workspace,
                        candidate.proposedRelativePath()
                );

        String originalClassName = extractClassName(
                candidate.proposedRelativePath()
        );

        String versionedClassName = extractClassName(
                versionedPath.relativePath()
        );

        List<String> warnings = new ArrayList<>(candidate.warnings());

        String rewrittenSource = candidate.source();

        if (!originalClassName.equals(versionedClassName)) {
            JavaTopLevelClassRenamer.RenameResult renameResult =
                    classRenamer.rename(
                            candidate.source(),
                            originalClassName,
                            versionedClassName
                    );

            if (renameResult.classDeclarationMatches() != 1) {
                throw new IllegalStateException(
                        "Class declaration rename failed. "
                                + "Expected 1 match, found: "
                                + renameResult.classDeclarationMatches()
                );
            }

            rewrittenSource = renameResult.source();

            warnings.add(
                    "Class renamed from "
                            + originalClassName
                            + " to "
                            + versionedClassName
                            + " for version "
                            + versionedPath.version()
            );
        }

        String rewrittenHash = calculateSha256(rewrittenSource);

        savePreWriteEvidence(
                caseId,
                candidate,
                candidateEvidence.getId(),
                approvalId,
                versionedPath,
                versionedClassName,
                rewrittenSource,
                rewrittenHash
        );

        fileWriter.writeNewFile(
                workspace,
                versionedPath.relativePath(),
                rewrittenSource
        );

        String gitStatus = captureGitStatus(workspace);

        PatternInformedTestWriteResult result =
                new PatternInformedTestWriteResult(
                        caseId,
                        approvalId,
                        candidateEvidence.getId(),
                        candidate.repositorySlug(),
                        candidate.sourceCommitSha(),
                        candidate.readiness().toString(),
                        candidate.compileConfidence(),
                        candidate.proposedRelativePath(),
                        versionedPath.relativePath(),
                        versionedPath.absolutePath().toString(),
                        versionedPath.version(),
                        rewrittenHash,
                        rewrittenSource.length(),
                        true,
                        true,
                        false,
                        false,
                        !gitStatus.isBlank(),
                        gitStatus,
                        warnings
                );

        saveEvidence(caseId, result);

        auditService.record(
                caseId,
                "PATTERN_INFORMED_TEST_WRITTEN",
                approval.getDecidedBy(),
                "approvalId="
                        + approvalId
                        + ", version="
                        + versionedPath.version()
                        + ", path="
                        + versionedPath.relativePath()
                        + ", confidence="
                        + candidate.compileConfidence()
        );

        return result;
    }

    private void validateCandidate(
            PatternInformedTestSourceCandidate candidate
    ) {
        if (candidate.readiness() != TestSourceReadiness.READY_FOR_REVIEW) {
            throw new IllegalStateException(
                    "Candidate is not ready for write. "
                            + "Current readiness: "
                            + candidate.readiness()
            );
        }

        if (candidate.source() == null || candidate.source().isBlank()) {
            throw new IllegalStateException(
                    "Candidate source is empty."
            );
        }

        if (candidate.fileWritten()) {
            throw new IllegalStateException(
                    "Candidate has already been written."
            );
        }

        if (candidate.testExecuted()) {
            throw new IllegalStateException(
                    "Candidate test has already been executed."
            );
        }

        if (!candidate.humanApprovalRequired()) {
            throw new IllegalStateException(
                    "Candidate must require human approval."
            );
        }

        if (!candidate.unresolvedSymbols().isEmpty()) {
            throw new IllegalStateException(
                    "Candidate has unresolved symbols: "
                            + String.join(", ", candidate.unresolvedSymbols())
            );
        }

        double minConfidence = properties.getPolicy()
                .getPatternTestMinConfidence();

        if (candidate.compileConfidence() < minConfidence) {
            throw new IllegalStateException(
                    "Pattern-informed candidate confidence is below write threshold. "
                            + "Required: "
                            + minConfidence
                            + ", actual: "
                            + candidate.compileConfidence()
            );
        }
    }

    private String extractClassName(String javaFilePath) {
        String normalized = javaFilePath.replace('\\', '/');

        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash >= 0
                ? normalized.substring(lastSlash + 1)
                : normalized;

        if (!fileName.endsWith(".java")) {
            throw new IllegalArgumentException(
                    "Not a Java file: " + javaFilePath
            );
        }

        return fileName.substring(0, fileName.length() - 5);
    }

    private void savePreWriteEvidence(
            UUID caseId,
            PatternInformedTestSourceCandidate candidate,
            UUID candidateEvidenceId,
            UUID approvalId,
            VersionedTestPathResolver.VersionedPath versionedPath,
            String versionedClassName,
            String rewrittenSource,
            String rewrittenHash
    ) {
        try {
            PatternInformedTestSourceCandidate preWriteCandidate =
                    new PatternInformedTestSourceCandidate(
                            candidate.caseId(),
                            candidate.repositorySlug(),
                            candidate.sourceCommitSha(),
                            candidate.patternPath(),
                            candidate.targetProductionClass(),
                            candidate.targetProductionMethod(),
                            candidate.proposedPackage(),
                            versionedClassName,
                            candidate.proposedMethodName(),
                            versionedPath.relativePath(),
                            candidate.framework(),
                            candidate.testStyle(),
                            rewrittenSource,
                            rewrittenHash,
                            candidate.readiness(),
                            candidate.compileConfidence(),
                            candidate.reusedImports(),
                            candidate.reusedAnnotations(),
                            candidate.detectedDependencies(),
                            candidate.unresolvedSymbols(),
                            candidate.assumptions(),
                            candidate.warnings(),
                            false,
                            false,
                            true
                    );

            evidenceService.save(
                    caseId,
                    EvidenceType.GENERATED_TEST,
                    PRE_WRITE_SOURCE,
                    objectMapper.writeValueAsString(preWriteCandidate),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save pre-write evidence.",
                    exception
            );
        }
    }

    private String captureGitStatus(Path workspace) {
        try {
            return gitService.hasChanges(workspace.toString())
                    ? gitService.readDiff(workspace.toString())
                    : "";

        } catch (Exception exception) {
            return "Error capturing git status: " + exception.getMessage();
        }
    }

    private void saveEvidence(
            UUID caseId,
            PatternInformedTestWriteResult result
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.GENERATED_TEST,
                    OUTPUT_SOURCE,
                    objectMapper.writeValueAsString(result),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save pattern-informed write result evidence.",
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return toHex(hash);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot calculate SHA-256.",
                    exception
            );
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
