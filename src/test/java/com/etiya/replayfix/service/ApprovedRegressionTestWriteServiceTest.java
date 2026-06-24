package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ApprovalRequestEntity;
import com.etiya.replayfix.domain.ApprovalStatus;
import com.etiya.replayfix.domain.ApprovalTargetType;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.GeneratedTestWriteResult;
import com.etiya.replayfix.model.RegressionTestPlan;
import com.etiya.replayfix.model.SourceCheckoutResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovedRegressionTestWriteServiceTest {

    private ApprovedRegressionTestWriteService writeService;
    private ReplayFixProperties properties;
    private EvidenceService evidenceService;
    private ApprovalService approvalService;
    private RegressionTestSourceRenderer renderer;
    private AtomicWorkspaceFileWriter fileWriter;
    private GitWorkspaceService gitWorkspaceService;
    private AuditService auditService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempWorkspace;

    @BeforeEach
    void setUp() {
        properties = mock(ReplayFixProperties.class);
        evidenceService = mock(EvidenceService.class);
        approvalService = mock(ApprovalService.class);
        renderer = new RegressionTestSourceRenderer();
        fileWriter = new AtomicWorkspaceFileWriter();
        gitWorkspaceService = mock(GitWorkspaceService.class);
        auditService = mock(AuditService.class);
        objectMapper = new ObjectMapper();

        ReplayFixProperties.Policy policy = new ReplayFixProperties.Policy();
        policy.setAllowGeneratedCodeWrite(true);
        when(properties.getPolicy()).thenReturn(policy);

        writeService = new ApprovedRegressionTestWriteService(
                properties,
                evidenceService,
                approvalService,
                renderer,
                fileWriter,
                gitWorkspaceService,
                auditService,
                objectMapper
        );
    }

    @Test
    void shouldThrowExceptionWhenPolicyDisablesWrite() {
        ReplayFixProperties.Policy policy = new ReplayFixProperties.Policy();
        policy.setAllowGeneratedCodeWrite(false);
        when(properties.getPolicy()).thenReturn(policy);

        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();

        assertThrows(
                IllegalStateException.class,
                () -> writeService.write(caseId, approvalId)
        );

        verify(evidenceService, never()).save(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldThrowExceptionWhenApprovalNotFound() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(planEvidence));

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenThrow(new IllegalStateException("Approved plan not found"));

        assertThrows(
                IllegalStateException.class,
                () -> writeService.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowExceptionWhenApprovalIsPending() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(planEvidence));

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenThrow(new IllegalStateException("Status is PENDING"));

        assertThrows(
                IllegalStateException.class,
                () -> writeService.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowExceptionWhenApprovalIsRejected() {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(planEvidence));

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenThrow(new IllegalStateException("Status is REJECTED"));

        assertThrows(
                IllegalStateException.class,
                () -> writeService.write(caseId, approvalId)
        );
    }

    @Test
    void shouldThrowExceptionWhenApprovalIdDoesNotMatch() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID wrongApprovalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        when(evidenceService.list(caseId)).thenReturn(List.of(planEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(wrongApprovalId);
        approval.setStatus(ApprovalStatus.APPROVED);

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenReturn(approval);

        assertThrows(
                IllegalStateException.class,
                () -> writeService.write(caseId, approvalId)
        );
    }

    @Test
    void shouldWriteFileWhenApprovedAndPolicyEnabled() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        EvidenceEntity checkoutEvidence = createCheckoutEvidence(caseId);

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, checkoutEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenReturn(approval);

        when(gitWorkspaceService.readDiff(anyString())).thenReturn("diff output");
        when(gitWorkspaceService.hasChanges(anyString())).thenReturn(true);

        GeneratedTestWriteResult result = writeService.write(caseId, approvalId);

        assertNotNull(result);
        assertEquals(caseId, result.caseId());
        assertEquals(approvalId, result.approvalId());
        assertTrue(result.sourceEvidenceSaved());
        assertTrue(result.fileWritten());
        assertFalse(result.testExecuted());
        assertTrue(result.gitDirty());

        Path writtenFile = Path.of(result.absolutePath());
        assertTrue(Files.exists(writtenFile));

        String content = Files.readString(writtenFile);
        assertTrue(content.contains("UnsupportedOperationException"));
        assertTrue(content.contains("Human approval was required"));

        verify(evidenceService, times(2)).save(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST),
                anyString(),
                anyString(),
                eq(true)
        );

        verify(auditService).record(
                eq(caseId),
                eq("APPROVED_GENERATED_TEST_WRITTEN"),
                eq("approver"),
                anyString()
        );
    }

    @Test
    void shouldSaveSourceEvidenceBeforeWriting() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        EvidenceEntity checkoutEvidence = createCheckoutEvidence(caseId);

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, checkoutEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenReturn(approval);

        when(gitWorkspaceService.readDiff(anyString())).thenReturn("");
        when(gitWorkspaceService.hasChanges(anyString())).thenReturn(true);

        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);

        writeService.write(caseId, approvalId);

        verify(evidenceService, times(2)).save(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST),
                sourceCaptor.capture(),
                anyString(),
                eq(true)
        );

        List<String> sources = sourceCaptor.getAllValues();
        assertTrue(sources.contains("approved-generated-test-source"));
        assertTrue(sources.contains("approved-generated-test-write-result"));
    }

    @Test
    void shouldNotOverwriteExistingFile() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        EvidenceEntity checkoutEvidence = createCheckoutEvidence(caseId);

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, checkoutEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenReturn(approval);

        Path existingFile = tempWorkspace.resolve("src/test/java/com/example/TestClass.java");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "existing content");

        assertThrows(
                IllegalStateException.class,
                () -> writeService.write(caseId, approvalId)
        );
    }

    @Test
    void shouldSetTestExecutedToFalse() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        EvidenceEntity checkoutEvidence = createCheckoutEvidence(caseId);

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, checkoutEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenReturn(approval);

        when(gitWorkspaceService.readDiff(anyString())).thenReturn("");
        when(gitWorkspaceService.hasChanges(anyString())).thenReturn(true);

        GeneratedTestWriteResult result = writeService.write(caseId, approvalId);

        assertFalse(result.testExecuted());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("not been executed")));
    }

    @Test
    void shouldRecordAuditEvent() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = createPlanEvidence(caseId, evidenceId);
        EvidenceEntity checkoutEvidence = createCheckoutEvidence(caseId);

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence, checkoutEvidence));

        ApprovalRequestEntity approval = new ApprovalRequestEntity();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy("approver");

        when(approvalService.requireApprovedRegressionTestPlan(caseId, evidenceId))
                .thenReturn(approval);

        when(gitWorkspaceService.readDiff(anyString())).thenReturn("");
        when(gitWorkspaceService.hasChanges(anyString())).thenReturn(true);

        writeService.write(caseId, approvalId);

        verify(auditService).record(
                eq(caseId),
                eq("APPROVED_GENERATED_TEST_WRITTEN"),
                eq("approver"),
                contains("approvalId=" + approvalId)
        );
    }

    private EvidenceEntity createPlanEvidence(UUID caseId, UUID evidenceId) {
        try {
            RegressionTestPlan plan = new RegressionTestPlan(
                    caseId,
                    "test-repo",
                    "abc123",
                    "JUnit 5",
                    "UNIT",
                    "Target",
                    "method",
                    "TestClass",
                    "shouldReproduceIssue",
                    "src/test/java/com/example/TestClass.java",
                    "scenario",
                    List.of("precondition"),
                    List.of("arrange"),
                    List.of("act"),
                    List.of("assert"),
                    List.of(),
                    List.of(),
                    Map.of(),
                    Map.of(),
                    "failure",
                    "success",
                    List.of(),
                    0.8,
                    "DETERMINISTIC_PLAN_ONLY",
                    false,
                    false,
                    true,
                    List.of()
            );

            EvidenceEntity entity = new EvidenceEntity();
            entity.setId(evidenceId);
            entity.setCaseId(caseId);
            entity.setEvidenceType(EvidenceType.GENERATED_TEST);
            entity.setSource("regression-test-plan");
            entity.setContentText(objectMapper.writeValueAsString(plan));

            return entity;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EvidenceEntity createCheckoutEvidence(UUID caseId) {
        try {
            SourceCheckoutResult checkout = new SourceCheckoutResult(
                    caseId,
                    "test-project",
                    "test-repo",
                    "main",
                    "abc123",
                    tempWorkspace.toString(),
                    true
            );

            EvidenceEntity entity = new EvidenceEntity();
            entity.setCaseId(caseId);
            entity.setEvidenceType(EvidenceType.SOURCE_CHECKOUT);
            entity.setSource("source-checkout");
            entity.setContentText(objectMapper.writeValueAsString(checkout));

            return entity;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
