package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ApprovalRequestEntity;
import com.etiya.replayfix.domain.ApprovalStatus;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovedLocalRegressionTestExecutionServiceTest {

    private ApprovedLocalRegressionTestExecutionService executionService;
    private ReplayFixProperties properties;
    private EvidenceService evidenceService;
    private ApprovalService approvalService;
    private SafeMavenTestRunner testRunner;
    private LocalTestExecutionClassifier classifier;
    private AuditService auditService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        properties = mock(ReplayFixProperties.class);
        evidenceService = mock(EvidenceService.class);
        approvalService = mock(ApprovalService.class);
        testRunner = mock(SafeMavenTestRunner.class);
        classifier = new LocalTestExecutionClassifier();
        auditService = mock(AuditService.class);
        objectMapper = new ObjectMapper();

        ReplayFixProperties.Policy policy = new ReplayFixProperties.Policy();
        policy.setAllowTestExecution(true);
        when(properties.getPolicy()).thenReturn(policy);

        executionService = new ApprovedLocalRegressionTestExecutionService(
                properties,
                evidenceService,
                approvalService,
                testRunner,
                classifier,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldThrowWhenPolicyDisablesExecution() {
        ReplayFixProperties.Policy policy = new ReplayFixProperties.Policy();
        policy.setAllowTestExecution(false);
        when(properties.getPolicy()).thenReturn(policy);

        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );

        verify(testRunner, never()).runSingleTest(any(), any(), any());
    }

    @Test
    void shouldThrowWhenApprovalNotFound() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        EvidenceEntity writeEvidence = createWriteEvidence(caseId, writeEvidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence));

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenThrow(new IllegalStateException("Approved test execution not found"));

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenApprovalIsPending() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        EvidenceEntity writeEvidence = createWriteEvidence(caseId, writeEvidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence));

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenThrow(new IllegalStateException("Status is PENDING"));

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenApprovalIsRejected() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        EvidenceEntity writeEvidence = createWriteEvidence(caseId, writeEvidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence));

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenThrow(new IllegalStateException("Status is REJECTED"));

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenApprovalIdDoesNotMatch() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID wrongApprovalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        EvidenceEntity writeEvidence = createWriteEvidence(caseId, writeEvidenceId);
        EvidenceEntity planEvidence = createPlanEvidence(caseId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence, planEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(wrongApprovalId);
        approval.setStatus(ApprovalStatus.APPROVED);

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenReturn(approval);

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenGeneratedFileDoesNotExist() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        GeneratedTestWriteResult writeResult = new GeneratedTestWriteResult(
                caseId,
                approvalId,
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                tempWorkspace.toString(),
                "src/test/java/TestFile.java",
                tempWorkspace.resolve("src/test/java/TestFile.java").toString(),
                "TestFile",
                "testMethod",
                "hash123",
                100,
                true,
                true,
                false,
                true,
                "",
                List.of()
        );

        EvidenceEntity writeEvidence = createWriteEvidenceWithResult(caseId, writeEvidenceId, writeResult);
        EvidenceEntity planEvidence = createPlanEvidence(caseId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence, planEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenReturn(approval);

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowWhenFileHashDoesNotMatch() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        Path testFile = tempWorkspace.resolve("src/test/java/TestFile.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "modified content");

        GeneratedTestWriteResult writeResult = new GeneratedTestWriteResult(
                caseId,
                approvalId,
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                tempWorkspace.toString(),
                "src/test/java/TestFile.java",
                testFile.toString(),
                "TestFile",
                "testMethod",
                "original-hash-that-does-not-match",
                100,
                true,
                true,
                false,
                true,
                "",
                List.of()
        );

        EvidenceEntity writeEvidence = createWriteEvidenceWithResult(caseId, writeEvidenceId, writeResult);
        EvidenceEntity planEvidence = createPlanEvidence(caseId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence, planEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenReturn(approval);

        assertThrows(
                IllegalStateException.class,
                () -> executionService.execute(caseId, approvalId)
        );
    }

    @Test
    void shouldExecuteTestWhenApprovedAndPolicyEnabled() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        String testContent = "public class TestFile { void testMethod() {} }";
        Path testFile = tempWorkspace.resolve("src/test/java/TestFile.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, testContent);

        String actualHash = calculateHash(testContent);

        GeneratedTestWriteResult writeResult = new GeneratedTestWriteResult(
                caseId,
                approvalId,
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                tempWorkspace.toString(),
                "src/test/java/TestFile.java",
                testFile.toString(),
                "TestFile",
                "testMethod",
                actualHash,
                testContent.length(),
                true,
                true,
                false,
                true,
                "",
                List.of()
        );

        EvidenceEntity writeEvidence = createWriteEvidenceWithResult(caseId, writeEvidenceId, writeResult);
        EvidenceEntity planEvidence = createPlanEvidence(caseId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence, planEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenReturn(approval);

        SafeProcessResult processResult = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                5000,
                1,
                false,
                "UnsupportedOperationException: ReplayLab regression scaffold"
        );

        when(testRunner.runSingleTest(any(), eq("TestFile"), eq("testMethod")))
                .thenReturn(processResult);

        LocalRegressionTestExecutionResult result = executionService.execute(caseId, approvalId);

        assertNotNull(result);
        assertEquals(caseId, result.caseId());
        assertEquals(approvalId, result.approvalId());
        assertTrue(result.generatedFileHashVerified());
        assertTrue(result.testExecuted());
        assertEquals(LocalTestExecutionStatus.SCAFFOLD_FAILURE_NOT_REPRODUCED, result.status());
        assertFalse(result.defectReproduced());
        assertTrue(result.scaffoldFailure());

        verify(testRunner).runSingleTest(any(), eq("TestFile"), eq("testMethod"));
        verify(evidenceService).save(eq(caseId), eq(EvidenceType.REPLAY_OUTPUT), any(), any(), eq(true));
        verify(auditService).record(eq(caseId), eq("LOCAL_REGRESSION_TEST_EXECUTED"), eq("approver"), anyString());
    }

    @Test
    void shouldMarkScaffoldFailureAsNotReproduced() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID writeEvidenceId = UUID.randomUUID();

        String testContent = "public class TestFile { void testMethod() {} }";
        Path testFile = tempWorkspace.resolve("src/test/java/TestFile.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, testContent);

        GeneratedTestWriteResult writeResult = createWriteResultWithHash(
                caseId, approvalId, testContent, testFile
        );

        setupMocksForExecution(caseId, approvalId, writeEvidenceId, writeResult);

        SafeProcessResult processResult = new SafeProcessResult(
                Instant.now(),
                Instant.now(),
                3000,
                1,
                false,
                "UnsupportedOperationException: ReplayLab regression scaffold"
        );

        when(testRunner.runSingleTest(any(), any(), any())).thenReturn(processResult);

        LocalRegressionTestExecutionResult result = executionService.execute(caseId, approvalId);

        assertFalse(result.defectReproduced());
        assertTrue(result.scaffoldFailure());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("scaffold")));
    }

    private EvidenceEntity createWriteEvidence(UUID caseId, UUID evidenceId) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.GENERATED_TEST);
        entity.setSource("approved-generated-test-write-result");
        entity.setContentText("{}");
        return entity;
    }

    private EvidenceEntity createWriteEvidenceWithResult(
            UUID caseId,
            UUID evidenceId,
            GeneratedTestWriteResult result
    ) throws Exception {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.GENERATED_TEST);
        entity.setSource("approved-generated-test-write-result");
        entity.setContentText(objectMapper.writeValueAsString(result));
        return entity;
    }

    private EvidenceEntity createPlanEvidence(UUID caseId) throws Exception {
        RegressionTestPlan plan = new RegressionTestPlan(
                caseId,
                "test-repo",
                "abc123",
                "JUnit 5",
                "UNIT",
                "Target",
                "method",
                "TestClass",
                "testMethod",
                "src/test/java/TestClass.java",
                "scenario",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "failure",
                "success",
                List.of(),
                0.8,
                "DETERMINISTIC",
                false,
                false,
                true,
                List.of()
        );

        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.GENERATED_TEST);
        entity.setSource("regression-test-plan");
        entity.setContentText(objectMapper.writeValueAsString(plan));
        return entity;
    }

    private GeneratedTestWriteResult createWriteResultWithHash(
            UUID caseId,
            UUID approvalId,
            String content,
            Path testFile
    ) {
        return new GeneratedTestWriteResult(
                caseId,
                approvalId,
                UUID.randomUUID(),
                "test-repo",
                "abc123",
                tempWorkspace.toString(),
                "src/test/java/TestFile.java",
                testFile.toString(),
                "TestFile",
                "testMethod",
                calculateHash(content),
                content.length(),
                true,
                true,
                false,
                true,
                "",
                List.of()
        );
    }

    private void setupMocksForExecution(
            UUID caseId,
            UUID approvalId,
            UUID writeEvidenceId,
            GeneratedTestWriteResult writeResult
    ) throws Exception {
        EvidenceEntity writeEvidence = createWriteEvidenceWithResult(caseId, writeEvidenceId, writeResult);
        EvidenceEntity planEvidence = createPlanEvidence(caseId);
        when(evidenceService.list(caseId)).thenReturn(List.of(writeEvidence, planEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedGeneratedTestExecution(caseId, writeEvidenceId))
                .thenReturn(approval);
    }

    private String calculateHash(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
