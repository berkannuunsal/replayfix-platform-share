package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.ApprovalRequestEntity;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.GeneratedTestSource;
import com.etiya.replaylab.model.GeneratedTestWriteResult;
import com.etiya.replaylab.model.RegressionTestPlan;
import com.etiya.replaylab.model.SourceCheckoutResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovedRegressionTestWriteService {

    private static final String PLAN_SOURCE =
            "regression-test-plan";

    private static final String GENERATED_SOURCE =
            "approved-generated-test-source";

    private static final String WRITE_RESULT_SOURCE =
            "approved-generated-test-write-result";

    private final ReplayLabProperties properties;
    private final EvidenceService evidenceService;
    private final ApprovalService approvalService;
    private final RegressionTestSourceRenderer renderer;
    private final AtomicWorkspaceFileWriter fileWriter;
    private final GitWorkspaceService gitWorkspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ApprovedRegressionTestWriteService(
            ReplayLabProperties properties,
            EvidenceService evidenceService,
            ApprovalService approvalService,
            RegressionTestSourceRenderer renderer,
            AtomicWorkspaceFileWriter fileWriter,
            GitWorkspaceService gitWorkspaceService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceService = evidenceService;
        this.approvalService = approvalService;
        this.renderer = renderer;
        this.fileWriter = fileWriter;
        this.gitWorkspaceService = gitWorkspaceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GeneratedTestWriteResult write(
            UUID caseId,
            UUID approvalId
    ) {
        if (!properties.getPolicy()
                .isAllowGeneratedCodeWrite()) {

            throw new IllegalStateException(
                    "Generated code write is disabled by policy."
            );
        }

        EvidenceEntity planEvidence =
                latestPlanEvidence(
                        caseId
                );

        ApprovalRequestEntity approval =
                approvalService
                        .requireApprovedRegressionTestPlan(
                                caseId,
                                planEvidence.getId()
                        );

        if (!approval.getId()
                .equals(approvalId)) {
            throw new IllegalStateException(
                    "Approval ID does not match "
                            + "the approved plan evidence."
            );
        }

        RegressionTestPlan plan =
                parse(
                        planEvidence,
                        RegressionTestPlan.class
                );

        SourceCheckoutResult checkout =
                latestSourceCheckout(
                        caseId
                );

        Path workspace =
                Path.of(
                        checkout.workspace()
                ).toAbsolutePath()
                        .normalize();

        String relativePath =
                normalizeRelativePath(
                        plan.proposedFilePath()
                );

        Path resolvedTarget =
                workspace.resolve(
                        relativePath
                ).normalize();

        if (!resolvedTarget.startsWith(
                workspace
        )) {
            throw new IllegalStateException(
                    "Generated file escapes workspace: "
                            + relativePath
            );
        }

        String source =
                renderer.render(plan);

        String sha256 =
                sha256(source);

        GeneratedTestSource beforeWrite =
                new GeneratedTestSource(
                        caseId,
                        approvalId,
                        planEvidence.getId(),
                        plan.repositorySlug(),
                        plan.sourceCommitSha(),
                        relativePath,
                        plan.proposedTestClass(),
                        plan.proposedTestMethod(),
                        "JAVA",
                        plan.framework(),
                        source,
                        sha256,
                        "DETERMINISTIC_APPROVED_WRITE",
                        true,
                        false
                );

        saveEvidence(
                caseId,
                GENERATED_SOURCE,
                beforeWrite
        );

        Path writtenFile =
                fileWriter.writeNewFile(
                        workspace,
                        relativePath,
                        source
                );

        String gitDiff =
                gitWorkspaceService
                        .readDiff(
                                workspace.toString()
                        );

        boolean dirty =
                gitWorkspaceService
                        .hasChanges(
                                workspace.toString()
                        );

        List<String> warnings =
                new ArrayList<>();

        warnings.add(
                "Generated file is a scaffold and has not been executed."
        );

        warnings.add(
                "No Git commit, push or pull request was created."
        );

        GeneratedTestWriteResult result =
                new GeneratedTestWriteResult(
                        caseId,
                        approvalId,
                        planEvidence.getId(),
                        plan.repositorySlug(),
                        plan.sourceCommitSha(),
                        workspace.toString(),
                        relativePath,
                        writtenFile.toString(),
                        plan.proposedTestClass(),
                        plan.proposedTestMethod(),
                        sha256,
                        source.length(),
                        true,
                        true,
                        false,
                        dirty,
                        truncate(
                                gitDiff,
                                50000
                        ),
                        warnings
                );

        saveEvidence(
                caseId,
                WRITE_RESULT_SOURCE,
                result
        );

        auditService.record(
                caseId,
                "APPROVED_GENERATED_TEST_WRITTEN",
                approval.getDecidedBy(),
                "approvalId="
                        + approvalId
                        + ", planEvidenceId="
                        + planEvidence.getId()
                        + ", path="
                        + relativePath
                        + ", sha256="
                        + sha256
        );

        return result;
    }

    private EvidenceEntity latestPlanEvidence(
            UUID caseId
    ) {
        return evidenceService.list(caseId)
                .stream()
                .filter(item ->
                        item.getEvidenceType()
                                == EvidenceType.GENERATED_TEST
                )
                .filter(item ->
                        PLAN_SOURCE.equals(
                                item.getSource()
                        )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Regression test plan evidence not found."
                        )
                );
    }

    private SourceCheckoutResult latestSourceCheckout(
            UUID caseId
    ) {
        EvidenceEntity evidence =
                evidenceService.list(caseId)
                        .stream()
                        .filter(item ->
                                item.getEvidenceType()
                                        == EvidenceType.SOURCE_CHECKOUT
                        )
                        .reduce(
                                (first, second) ->
                                        second
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Source checkout evidence not found."
                                )
                        );

        return parse(
                evidence,
                SourceCheckoutResult.class
        );
    }

    private void saveEvidence(
            UUID caseId,
            String source,
            Object value
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.GENERATED_TEST,
                    source,
                    objectMapper.writeValueAsString(
                            value
                    ),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save generated test evidence. "
                            + "source="
                            + source,
                    exception
            );
        }
    }

    private <T> T parse(
            EvidenceEntity evidence,
            Class<T> type
    ) {
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

    private String normalizeRelativePath(
            String value
    ) {
        if (value == null) {
            return "";
        }

        String normalized =
                value.replace(
                        '\\',
                        '/'
                ).trim();

        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.contains(":/")) {

            throw new IllegalArgumentException(
                    "Unsafe generated test path: "
                            + normalized
            );
        }

        if (!normalized.startsWith(
                "src/test/java/"
        )) {
            throw new IllegalArgumentException(
                    "Generated Java test must be under src/test/java: "
                            + normalized
            );
        }

        return normalized;
    }

    private String sha256(
            String value
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] hash =
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of()
                    .formatHex(hash);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot calculate SHA-256.",
                    exception
            );
        }
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }

        return value.length()
                <= maxLength
                ? value
                : value.substring(
                        0,
                        maxLength
                );
    }
}
