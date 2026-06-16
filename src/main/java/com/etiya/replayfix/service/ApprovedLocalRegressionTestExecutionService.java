package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ApprovalRequestEntity;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.GeneratedTestWriteResult;
import com.etiya.replayfix.model.LocalRegressionTestExecutionResult;
import com.etiya.replayfix.model.RegressionTestPlan;
import com.etiya.replayfix.model.SafeProcessResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovedLocalRegressionTestExecutionService {

    private static final String PLAN_SOURCE =
            "regression-test-plan";

    private static final String WRITE_RESULT_SOURCE =
            "approved-generated-test-write-result";

    private static final String EXECUTION_SOURCE =
            "local-regression-test-execution";

    private final ReplayFixProperties properties;
    private final EvidenceService evidenceService;
    private final ApprovalService approvalService;
    private final SafeMavenTestRunner testRunner;
    private final LocalTestExecutionClassifier classifier;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ApprovedLocalRegressionTestExecutionService(
            ReplayFixProperties properties,
            EvidenceService evidenceService,
            ApprovalService approvalService,
            SafeMavenTestRunner testRunner,
            LocalTestExecutionClassifier classifier,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceService = evidenceService;
        this.approvalService = approvalService;
        this.testRunner = testRunner;
        this.classifier = classifier;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public LocalRegressionTestExecutionResult execute(
            UUID caseId,
            UUID approvalId
    ) {
        if (!properties.getPolicy()
                .isAllowTestExecution()) {

            throw new IllegalStateException(
                    "Local test execution is disabled by policy."
            );
        }

        EvidenceEntity writeResultEvidence =
                latestRequired(
                        caseId,
                        EvidenceType.GENERATED_TEST,
                        WRITE_RESULT_SOURCE
                );

        ApprovalRequestEntity approval =
                approvalService
                        .requireApprovedGeneratedTestExecution(
                                caseId,
                                writeResultEvidence.getId()
                        );

        if (!approval.getId()
                .equals(approvalId)) {
            throw new IllegalStateException(
                    "Approval ID does not match "
                            + "the approved generated test execution."
            );
        }

        GeneratedTestWriteResult writeResult =
                parse(
                        writeResultEvidence,
                        GeneratedTestWriteResult.class
                );

        EvidenceEntity planEvidence =
                latestRequired(
                        caseId,
                        EvidenceType.GENERATED_TEST,
                        PLAN_SOURCE
                );

        RegressionTestPlan plan =
                parse(
                        planEvidence,
                        RegressionTestPlan.class
                );

        Path workspace =
                Path.of(
                        writeResult.workspace()
                ).toAbsolutePath()
                        .normalize();

        Path testFile =
                workspace.resolve(
                        writeResult.relativePath()
                ).normalize();

        if (!testFile.startsWith(
                workspace
        )) {
            throw new IllegalStateException(
                    "Generated test path escapes workspace."
            );
        }

        if (!Files.isRegularFile(
                testFile
        )) {
            throw new IllegalStateException(
                    "Generated test file does not exist: "
                            + testFile
            );
        }

        String actualHash =
                sha256(
                        readFile(testFile)
                );

        if (!actualHash.equalsIgnoreCase(
                writeResult.contentSha256()
        )) {
            throw new IllegalStateException(
                    "Generated test file hash does not match "
                            + "the approved write result."
            );
        }

        SafeProcessResult process;

        try {
            process =
                    testRunner.runSingleTest(
                            workspace,
                            writeResult.generatedClassName(),
                            writeResult.generatedMethodName()
                    );

        } catch (Exception exception) {
            LocalRegressionTestExecutionResult failed =
                    infrastructureFailureResult(
                            caseId,
                            approvalId,
                            writeResultEvidence.getId(),
                            writeResult,
                            exception
                    );

            saveExecution(
                    caseId,
                    failed
            );

            return failed;
        }

        var classification =
                classifier.classify(
                        process,
                        plan
                );

        List<String> warnings =
                new ArrayList<>();

        if (classification.scaffoldFailure()) {
            warnings.add(
                    "The failure was caused by the generated scaffold, "
                            + "not by a verified reproduction of the defect."
            );
        }

        if (!classification.defectReproduced()) {
            warnings.add(
                    "Minimum fix generation must remain blocked."
            );
        }

        LocalRegressionTestExecutionResult result =
                new LocalRegressionTestExecutionResult(
                        caseId,
                        approvalId,
                        writeResultEvidence.getId(),
                        writeResult.repositorySlug(),
                        writeResult.workspace(),
                        writeResult.relativePath(),
                        writeResult.generatedClassName(),
                        writeResult.generatedMethodName(),
                        writeResult.generatedClassName()
                                + "#"
                                + writeResult.generatedMethodName(),
                        resolveExecutableLabel(
                                writeResult.workspace()
                        ),
                        List.of(
                                "-B",
                                "-Dtest="
                                        + writeResult.generatedClassName()
                                        + "#"
                                        + writeResult.generatedMethodName(),
                                "-DfailIfNoTests=false",
                                "test"
                        ),
                        process.startedAt(),
                        process.finishedAt(),
                        process.durationMs(),
                        process.exitCode(),
                        process.timedOut(),
                        true,
                        true,
                        classification.status(),
                        classification.defectReproduced(),
                        classification.scaffoldFailure(),
                        process.output(),
                        classification.matchedSignals(),
                        warnings
                );

        saveExecution(
                caseId,
                result
        );

        auditService.record(
                caseId,
                "LOCAL_REGRESSION_TEST_EXECUTED",
                approval.getDecidedBy(),
                "approvalId="
                        + approvalId
                        + ", status="
                        + result.status()
                        + ", exitCode="
                        + result.exitCode()
                        + ", defectReproduced="
                        + result.defectReproduced()
        );

        return result;
    }

    private LocalRegressionTestExecutionResult
    infrastructureFailureResult(
            UUID caseId,
            UUID approvalId,
            UUID writeEvidenceId,
            GeneratedTestWriteResult writeResult,
            Exception exception
    ) {
        Instant now =
                Instant.now();

        return new LocalRegressionTestExecutionResult(
                caseId,
                approvalId,
                writeEvidenceId,
                writeResult.repositorySlug(),
                writeResult.workspace(),
                writeResult.relativePath(),
                writeResult.generatedClassName(),
                writeResult.generatedMethodName(),
                writeResult.generatedClassName()
                        + "#"
                        + writeResult.generatedMethodName(),
                "",
                List.of(),
                now,
                now,
                0,
                null,
                false,
                true,
                false,
                com.etiya.replayfix.model
                        .LocalTestExecutionStatus
                        .INFRASTRUCTURE_FAILURE,
                false,
                false,
                rootCauseMessage(exception),
                List.of(),
                List.of(
                        "Maven process could not be started."
                )
        );
    }

    private void saveExecution(
            UUID caseId,
            LocalRegressionTestExecutionResult result
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.REPLAY_OUTPUT,
                    EXECUTION_SOURCE,
                    objectMapper.writeValueAsString(
                            result
                    ),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save local test execution evidence.",
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
                .filter(item ->
                        item.getEvidenceType()
                                == type
                )
                .filter(item ->
                        source.equals(
                                item.getSource()
                        )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Required evidence not found. type="
                                        + type
                                        + ", source="
                                        + source
                        )
                );
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

    private String readFile(
            Path path
    ) {
        try {
            return Files.readString(
                    path,
                    StandardCharsets.UTF_8
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot read generated test file.",
                    exception
            );
        }
    }

    private String sha256(
            String value
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            )
                    );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot calculate generated test hash.",
                    exception
            );
        }
    }

    private String resolveExecutableLabel(
            String workspace
    ) {
        Path root =
                Path.of(workspace);

        boolean windows =
                System.getProperty("os.name")
                        .toLowerCase()
                        .contains("win");

        Path wrapper =
                root.resolve(
                        windows
                                ? "mvnw.cmd"
                                : "mvnw"
                );

        return Files.isRegularFile(wrapper)
                ? wrapper.getFileName()
                        .toString()
                : properties.getPolicy()
                        .getMavenExecutable();
    }

    private String rootCauseMessage(
            Throwable throwable
    ) {
        Throwable root =
                throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass()
                .getSimpleName()
                + ": "
                + root.getMessage();
    }
}
