package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.ApprovalRequestEntity;
import com.etiya.replaylab.domain.ApprovalStatus;
import com.etiya.replaylab.domain.ApprovalTargetType;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.ApprovalRequestView;
import com.etiya.replaylab.repository.ApprovalRequestRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {

    private ApprovalService approvalService;
    private ApprovalRequestRepository repository;
    private EvidenceService evidenceService;
    private AuditService auditService;
    private ReplayLabNotificationService notificationService;
    private ReplayCaseRepository caseRepository;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        evidenceService = mock(EvidenceService.class);
        auditService = mock(AuditService.class);
        notificationService = mock(ReplayLabNotificationService.class);
        caseRepository = mock(ReplayCaseRepository.class);

        approvalService = new ApprovalService(
                repository,
                evidenceService,
                auditService,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                notificationService,
                caseRepository
        );
    }

    @Test
    void shouldCreatePendingApprovalRequestWhenPlanExists() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = new EvidenceEntity();
        planEvidence.setId(evidenceId);
        planEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        planEvidence.setSource("regression-test-plan");

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence));

        when(repository.findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                eq(caseId),
                eq(ApprovalTargetType.REGRESSION_TEST_PLAN),
                eq(evidenceId),
                eq(ApprovalStatus.PENDING)
        )).thenReturn(Optional.empty());

        ApprovalRequestEntity savedEntity = new ApprovalRequestEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setCaseId(caseId);
        savedEntity.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        savedEntity.setTargetEvidenceId(evidenceId);
        savedEntity.setStatus(ApprovalStatus.PENDING);
        savedEntity.setRequestedBy("actor");

        when(repository.save(any(ApprovalRequestEntity.class)))
                .thenReturn(savedEntity);

        ApprovalRequestView result = approvalService
                .createRegressionTestPlanApproval(
                        caseId,
                        "actor",
                        "test comment"
                );

        assertNotNull(result);
        assertEquals(ApprovalStatus.PENDING, result.status());
        assertEquals(ApprovalTargetType.REGRESSION_TEST_PLAN, result.targetType());
        assertFalse(result.allowsGeneratedTestWrite());

        verify(repository).save(any(ApprovalRequestEntity.class));
        verify(auditService).record(
                eq(caseId),
                eq("REGRESSION_TEST_APPROVAL_REQUESTED"),
                eq("actor"),
                anyString()
        );
    }

    @Test
    void shouldReturnExistingPendingRequestWhenAlreadyExists() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity planEvidence = new EvidenceEntity();
        planEvidence.setId(evidenceId);
        planEvidence.setEvidenceType(EvidenceType.GENERATED_TEST);
        planEvidence.setSource("regression-test-plan");

        ApprovalRequestEntity existingEntity = new ApprovalRequestEntity();
        existingEntity.setId(UUID.randomUUID());
        existingEntity.setCaseId(caseId);
        existingEntity.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        existingEntity.setTargetEvidenceId(evidenceId);
        existingEntity.setStatus(ApprovalStatus.PENDING);
        existingEntity.setRequestedBy("actor");

        when(evidenceService.list(caseId))
                .thenReturn(List.of(planEvidence));

        when(repository.findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                eq(caseId),
                eq(ApprovalTargetType.REGRESSION_TEST_PLAN),
                eq(evidenceId),
                eq(ApprovalStatus.PENDING)
        )).thenReturn(Optional.of(existingEntity));

        ApprovalRequestView result = approvalService
                .createRegressionTestPlanApproval(
                        caseId,
                        "actor",
                        "test comment"
                );

        assertNotNull(result);
        assertEquals(ApprovalStatus.PENDING, result.status());

        verify(repository, never()).save(any());
    }

    @Test
    void shouldCreatePendingApprovalRequestForFailingRegressionTestDraft() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        EvidenceEntity draftEvidence = new EvidenceEntity();
        draftEvidence.setId(evidenceId);
        draftEvidence.setEvidenceType(EvidenceType.FAILING_REGRESSION_TEST_DRAFT);
        draftEvidence.setSource("failing-regression-test-draft");

        when(evidenceService.list(caseId))
                .thenReturn(List.of(draftEvidence));

        when(repository.findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                eq(caseId),
                eq(ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT),
                eq(evidenceId),
                eq(ApprovalStatus.PENDING)
        )).thenReturn(Optional.empty());

        ApprovalRequestEntity savedEntity = new ApprovalRequestEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setCaseId(caseId);
        savedEntity.setTargetType(ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT);
        savedEntity.setTargetEvidenceId(evidenceId);
        savedEntity.setTargetEvidenceType(EvidenceType.FAILING_REGRESSION_TEST_DRAFT.name());
        savedEntity.setTargetEvidenceSource("failing-regression-test-draft");
        savedEntity.setStatus(ApprovalStatus.PENDING);
        savedEntity.setRequestedBy("actor");

        when(repository.save(any(ApprovalRequestEntity.class)))
                .thenReturn(savedEntity);

        ApprovalRequestView result = approvalService
                .createFailingRegressionTestDraftApproval(
                        caseId,
                        "actor",
                        "review generated draft"
                );

        assertNotNull(result);
        assertEquals(ApprovalStatus.PENDING, result.status());
        assertEquals(ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT, result.targetType());
        assertEquals(EvidenceType.FAILING_REGRESSION_TEST_DRAFT.name(), result.targetEvidenceType());
        assertEquals("failing-regression-test-draft", result.targetEvidenceSource());
        assertFalse(result.allowsGeneratedTestWrite());
        assertFalse(result.allowsTestExecution());
        assertFalse(result.allowsPatternInformedTestWrite());

        verify(repository).save(any(ApprovalRequestEntity.class));
        verify(auditService).record(
                eq(caseId),
                eq("FAILING_REGRESSION_TEST_DRAFT_APPROVAL_REQUESTED"),
                eq("actor"),
                anyString()
        );
    }

    @Test
    void shouldApproveRequestAndSetAllowsWrite() {
        UUID approvalId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(approvalId);
        entity.setCaseId(caseId);
        entity.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        entity.setStatus(ApprovalStatus.PENDING);

        ApprovalRequestEntity approvedEntity = new ApprovalRequestEntity();
        approvedEntity.setId(approvalId);
        approvedEntity.setCaseId(caseId);
        approvedEntity.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        approvedEntity.setStatus(ApprovalStatus.APPROVED);
        approvedEntity.setDecidedBy("approver");

        when(repository.findById(approvalId))
                .thenReturn(Optional.of(entity));

        when(repository.save(any(ApprovalRequestEntity.class)))
                .thenReturn(approvedEntity);

        ApprovalRequestView result = approvalService.approve(
                approvalId,
                "approver",
                "Approved"
        );

        assertEquals(ApprovalStatus.APPROVED, result.status());
        assertTrue(result.allowsGeneratedTestWrite());

        ArgumentCaptor<ApprovalRequestEntity> captor =
                ArgumentCaptor.forClass(ApprovalRequestEntity.class);
        verify(repository).save(captor.capture());

        ApprovalRequestEntity saved = captor.getValue();
        assertEquals(ApprovalStatus.APPROVED, saved.getStatus());
        assertEquals("approver", saved.getDecidedBy());
        assertNotNull(saved.getDecidedAt());

        verify(auditService).record(
                eq(caseId),
                eq("REGRESSION_TEST_PLAN_APPROVED"),
                eq("approver"),
                anyString()
        );
    }

    @Test
    void shouldRejectRequestAndNotAllowWrite() {
        UUID approvalId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(approvalId);
        entity.setCaseId(caseId);
        entity.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        entity.setStatus(ApprovalStatus.PENDING);

        ApprovalRequestEntity rejectedEntity = new ApprovalRequestEntity();
        rejectedEntity.setId(approvalId);
        rejectedEntity.setCaseId(caseId);
        rejectedEntity.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        rejectedEntity.setStatus(ApprovalStatus.REJECTED);
        rejectedEntity.setDecidedBy("rejector");

        when(repository.findById(approvalId))
                .thenReturn(Optional.of(entity));

        when(repository.save(any(ApprovalRequestEntity.class)))
                .thenReturn(rejectedEntity);

        ApprovalRequestView result = approvalService.reject(
                approvalId,
                "rejector",
                "Not ready"
        );

        assertEquals(ApprovalStatus.REJECTED, result.status());
        assertFalse(result.allowsGeneratedTestWrite());

        verify(auditService).record(
                eq(caseId),
                eq("REGRESSION_TEST_PLAN_REJECTED"),
                eq("rejector"),
                anyString()
        );
    }

    @Test
    void shouldThrowExceptionWhenApprovingAlreadyDecided() {
        UUID approvalId = UUID.randomUUID();

        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(approvalId);
        entity.setStatus(ApprovalStatus.APPROVED);

        when(repository.findById(approvalId))
                .thenReturn(Optional.of(entity));

        assertThrows(
                IllegalStateException.class,
                () -> approvalService.approve(
                        approvalId,
                        "actor",
                        "comment"
                )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void shouldThrowExceptionWhenRejectingAlreadyDecided() {
        UUID approvalId = UUID.randomUUID();

        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(approvalId);
        entity.setStatus(ApprovalStatus.REJECTED);

        when(repository.findById(approvalId))
                .thenReturn(Optional.of(entity));

        assertThrows(
                IllegalStateException.class,
                () -> approvalService.reject(
                        approvalId,
                        "actor",
                        "comment"
                )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void shouldThrowExceptionWhenActorIsBlank() {
        UUID caseId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> approvalService
                        .createRegressionTestPlanApproval(
                                caseId,
                                "",
                                "comment"
                        )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void shouldThrowExceptionWhenPlanEvidenceNotFound() {
        UUID caseId = UUID.randomUUID();

        when(evidenceService.list(caseId))
                .thenReturn(List.of());

        assertThrows(
                IllegalStateException.class,
                () -> approvalService
                        .createRegressionTestPlanApproval(
                                caseId,
                                "actor",
                                "comment"
                        )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void shouldRequireApprovedRegressionTestPlan() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setTargetEvidenceId(evidenceId);
        entity.setStatus(ApprovalStatus.APPROVED);

        when(repository.findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                eq(caseId),
                eq(ApprovalTargetType.REGRESSION_TEST_PLAN),
                eq(evidenceId),
                eq(ApprovalStatus.APPROVED)
        )).thenReturn(Optional.of(entity));

        ApprovalRequestEntity result = approvalService
                .requireApprovedRegressionTestPlan(
                        caseId,
                        evidenceId
                );

        assertNotNull(result);
        assertEquals(ApprovalStatus.APPROVED, result.getStatus());
    }

    @Test
    void shouldThrowExceptionWhenApprovedPlanNotFound() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        when(repository.findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> approvalService
                        .requireApprovedRegressionTestPlan(
                                caseId,
                                evidenceId
                        )
        );
    }

    @Test
    void shouldSetAllowsWriteOnlyForApprovedRegressionTestPlan() {
        ApprovalRequestEntity approved = new ApprovalRequestEntity();
        approved.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        approved.setStatus(ApprovalStatus.APPROVED);

        ApprovalRequestEntity pending = new ApprovalRequestEntity();
        pending.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        pending.setStatus(ApprovalStatus.PENDING);

        ApprovalRequestEntity rejected = new ApprovalRequestEntity();
        rejected.setTargetType(ApprovalTargetType.REGRESSION_TEST_PLAN);
        rejected.setStatus(ApprovalStatus.REJECTED);

        UUID caseId = UUID.randomUUID();

        when(repository.findByCaseIdOrderByRequestedAtDesc(caseId))
                .thenReturn(List.of(approved, pending, rejected));

        List<ApprovalRequestView> results = approvalService.list(caseId);

        assertEquals(3, results.size());
        assertTrue(results.get(0).allowsGeneratedTestWrite());
        assertFalse(results.get(1).allowsGeneratedTestWrite());
        assertFalse(results.get(2).allowsGeneratedTestWrite());
    }
}
