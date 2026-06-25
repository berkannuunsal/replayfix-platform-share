package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.ApprovalRequestEntity;
import com.etiya.replaylab.domain.ApprovalStatus;
import com.etiya.replaylab.domain.ApprovalTargetType;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.ApprovalRequestView;
import com.etiya.replaylab.model.PatternInformedTestSourceCandidate;
import com.etiya.replaylab.model.TestSourceReadiness;
import com.etiya.replaylab.repository.ApprovalRequestRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovalService {

    private static final String TEST_PLAN_SOURCE =
            "regression-test-plan";

    private static final String GENERATED_WRITE_RESULT_SOURCE =
            "approved-generated-test-write-result";

    private static final String FAILING_REGRESSION_TEST_DRAFT_SOURCE =
            "failing-regression-test-draft";

    private static final String PATTERN_CANDIDATE_SOURCE =
            "pattern-informed-test-source-candidate";

    private final ApprovalRequestRepository repository;
    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ReplayLabNotificationService notificationService;
    private final ReplayCaseRepository caseRepository;

    public ApprovalService(
            ApprovalRequestRepository repository,
            EvidenceService evidenceService,
            AuditService auditService,
            ObjectMapper objectMapper,
            ReplayLabNotificationService notificationService,
            ReplayCaseRepository caseRepository
    ) {
        this.repository = repository;
        this.evidenceService = evidenceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.caseRepository = caseRepository;
    }

    @Transactional
    public ApprovalRequestView createRegressionTestPlanApproval(
            UUID caseId,
            String actor,
            String comment
    ) {
        String normalizedActor =
                requireActor(actor);

        EvidenceEntity planEvidence =
                latestRegressionTestPlan(caseId);

        var existingPending =
                repository
                        .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                                caseId,
                                ApprovalTargetType.REGRESSION_TEST_PLAN,
                                planEvidence.getId(),
                                ApprovalStatus.PENDING
                        );

        if (existingPending.isPresent()) {
            return toView(
                    existingPending.get()
            );
        }

        ApprovalRequestEntity entity =
                new ApprovalRequestEntity();

        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setTargetType(
                ApprovalTargetType.REGRESSION_TEST_PLAN
        );
        entity.setTargetEvidenceId(
                planEvidence.getId()
        );
        entity.setTargetEvidenceType(
                EvidenceType.GENERATED_TEST.name()
        );
        entity.setTargetEvidenceSource(
                TEST_PLAN_SOURCE
        );
        entity.setStatus(
                ApprovalStatus.PENDING
        );
        entity.setRequestedBy(
                normalizedActor
        );
        entity.setRequestComment(
                normalizeComment(comment)
        );
        entity.setRequestedAt(
                Instant.now()
        );

        ApprovalRequestEntity saved =
                repository.save(entity);

        auditService.record(
                caseId,
                "REGRESSION_TEST_APPROVAL_REQUESTED",
                normalizedActor,
                "approvalId="
                        + saved.getId()
                        + ", evidenceId="
                        + saved.getTargetEvidenceId()
        );

        return toView(saved);
    }

    @Transactional
    public ApprovalRequestView createFailingRegressionTestDraftApproval(
            UUID caseId,
            String actor,
            String comment
    ) {
        String normalizedActor =
                requireActor(actor);

        EvidenceEntity draftEvidence =
                latestEvidence(
                        caseId,
                        EvidenceType.FAILING_REGRESSION_TEST_DRAFT,
                        FAILING_REGRESSION_TEST_DRAFT_SOURCE
                );

        var existingPending =
                repository
                        .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                                caseId,
                                ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT,
                                draftEvidence.getId(),
                                ApprovalStatus.PENDING
                        );

        if (existingPending.isPresent()) {
            return toView(
                    existingPending.get()
            );
        }

        ApprovalRequestEntity entity =
                new ApprovalRequestEntity();

        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setTargetType(
                ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT
        );
        entity.setTargetEvidenceId(
                draftEvidence.getId()
        );
        entity.setTargetEvidenceType(
                EvidenceType.FAILING_REGRESSION_TEST_DRAFT.name()
        );
        entity.setTargetEvidenceSource(
                FAILING_REGRESSION_TEST_DRAFT_SOURCE
        );
        entity.setStatus(
                ApprovalStatus.PENDING
        );
        entity.setRequestedBy(
                normalizedActor
        );
        entity.setRequestComment(
                normalizeComment(comment)
        );
        entity.setRequestedAt(
                Instant.now()
        );

        ApprovalRequestEntity saved =
                repository.save(entity);

        auditService.record(
                caseId,
                "FAILING_REGRESSION_TEST_DRAFT_APPROVAL_REQUESTED",
                normalizedActor,
                "approvalId="
                        + saved.getId()
                        + ", evidenceId="
                        + saved.getTargetEvidenceId()
        );

        return toView(saved);
    }

    @Transactional
    public ApprovalRequestView approve(
            UUID approvalId,
            String actor,
            String comment
    ) {
        return decide(
                approvalId,
                ApprovalStatus.APPROVED,
                actor,
                comment
        );
    }

    @Transactional
    public ApprovalRequestView reject(
            UUID approvalId,
            String actor,
            String comment
    ) {
        return decide(
                approvalId,
                ApprovalStatus.REJECTED,
                actor,
                comment
        );
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestView> list(
            UUID caseId
    ) {
        return repository
                .findByCaseIdOrderByRequestedAtDesc(
                        caseId
                )
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalRequestEntity requireApprovedRegressionTestPlan(
            UUID caseId,
            UUID evidenceId
    ) {
        return repository
                .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                        caseId,
                        ApprovalTargetType.REGRESSION_TEST_PLAN,
                        evidenceId,
                        ApprovalStatus.APPROVED
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Approved regression test plan "
                                        + "was not found. caseId="
                                        + caseId
                                        + ", evidenceId="
                                        + evidenceId
                        )
                );
    }

    @Transactional(readOnly = true)
    public ApprovalRequestEntity requireApprovedFailingRegressionTestDraft(
            UUID caseId,
            UUID evidenceId
    ) {
        return repository
                .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                        caseId,
                        ApprovalTargetType.FAILING_REGRESSION_TEST_DRAFT,
                        evidenceId,
                        ApprovalStatus.APPROVED
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Approved failing regression test draft "
                                        + "was not found. caseId="
                                        + caseId
                                        + ", evidenceId="
                                        + evidenceId
                        )
                );
    }

    private ApprovalRequestView decide(
            UUID approvalId,
            ApprovalStatus decision,
            String actor,
            String comment
    ) {
        String normalizedActor =
                requireActor(actor);

        ApprovalRequestEntity entity =
                repository.findById(
                        approvalId
                ).orElseThrow(() ->
                        new IllegalArgumentException(
                                "Approval request not found: "
                                        + approvalId
                        )
                );

        if (entity.getStatus()
                != ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "Approval request is already decided. "
                            + "Current status: "
                            + entity.getStatus()
            );
        }

        entity.setStatus(decision);
        entity.setDecidedBy(
                normalizedActor
        );
        entity.setDecisionComment(
                normalizeComment(comment)
        );
        entity.setDecidedAt(
                Instant.now()
        );

        ApprovalRequestEntity saved =
                repository.save(entity);

        String auditAction = determineAuditAction(
                saved.getTargetType(),
                decision
        );

        auditService.record(
                saved.getCaseId(),
                auditAction,
                normalizedActor,
                "approvalId="
                        + saved.getId()
                        + ", evidenceId="
                        + saved.getTargetEvidenceId()
                        + ", comment="
                        + normalizeComment(comment)
        );

        return toView(saved);
    }

    private EvidenceEntity latestRegressionTestPlan(
            UUID caseId
    ) {
        return evidenceService.list(caseId)
                .stream()
                .filter(item ->
                        item.getEvidenceType()
                                == EvidenceType.GENERATED_TEST
                )
                .filter(item ->
                        TEST_PLAN_SOURCE.equals(
                                item.getSource()
                        )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Regression test plan evidence "
                                        + "was not found for case: "
                                        + caseId
                        )
                );
    }

    private ApprovalRequestView toView(
            ApprovalRequestEntity entity
    ) {
        boolean allowsWrite =
                entity.getTargetType()
                        == ApprovalTargetType.REGRESSION_TEST_PLAN
                        && entity.getStatus()
                        == ApprovalStatus.APPROVED;

        boolean allowsExecution =
                entity.getTargetType()
                        == ApprovalTargetType.GENERATED_TEST_EXECUTION
                        && entity.getStatus()
                        == ApprovalStatus.APPROVED;

        boolean allowsPatternWrite =
                entity.getTargetType()
                        == ApprovalTargetType.PATTERN_INFORMED_TEST_SOURCE
                        && entity.getStatus()
                        == ApprovalStatus.APPROVED;

        return new ApprovalRequestView(
                entity.getId(),
                entity.getCaseId(),
                entity.getTargetType(),
                entity.getTargetEvidenceId(),
                entity.getTargetEvidenceType(),
                entity.getTargetEvidenceSource(),
                entity.getStatus(),
                entity.getRequestedBy(),
                entity.getRequestComment(),
                entity.getRequestedAt(),
                entity.getDecidedBy(),
                entity.getDecisionComment(),
                entity.getDecidedAt(),
                allowsWrite,
                allowsExecution,
                allowsPatternWrite
        );
    }

    private String requireActor(
            String actor
    ) {
        if (actor == null
                || actor.isBlank()) {
            throw new IllegalArgumentException(
                    "Actor is required."
            );
        }

        return actor.trim();
    }

    private String normalizeComment(
            String comment
    ) {
        if (comment == null) {
            return "";
        }

        String value =
                comment.trim();

        return value.length()
                <= 4000
                ? value
                : value.substring(
                        0,
                        4000
                );
    }

    @Transactional
    public ApprovalRequestView createGeneratedTestExecutionApproval(
            UUID caseId,
            String actor,
            String comment
    ) {
        String normalizedActor =
                requireActor(actor);

        EvidenceEntity writeResultEvidence =
                latestGeneratedWriteResult(
                        caseId
                );

        var existingPending =
                repository
                        .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                                caseId,
                                ApprovalTargetType.GENERATED_TEST_EXECUTION,
                                writeResultEvidence.getId(),
                                ApprovalStatus.PENDING
                        );

        if (existingPending.isPresent()) {
            return toView(
                    existingPending.get()
            );
        }

        ApprovalRequestEntity entity =
                new ApprovalRequestEntity();

        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setTargetType(
                ApprovalTargetType.GENERATED_TEST_EXECUTION
        );
        entity.setTargetEvidenceId(
                writeResultEvidence.getId()
        );
        entity.setTargetEvidenceType(
                EvidenceType.GENERATED_TEST.name()
        );
        entity.setTargetEvidenceSource(
                GENERATED_WRITE_RESULT_SOURCE
        );
        entity.setStatus(
                ApprovalStatus.PENDING
        );
        entity.setRequestedBy(
                normalizedActor
        );
        entity.setRequestComment(
                normalizeComment(comment)
        );
        entity.setRequestedAt(
                Instant.now()
        );

        ApprovalRequestEntity saved =
                repository.save(entity);

        auditService.record(
                caseId,
                "GENERATED_TEST_EXECUTION_APPROVAL_REQUESTED",
                normalizedActor,
                "approvalId="
                        + saved.getId()
                        + ", evidenceId="
                        + saved.getTargetEvidenceId()
        );

        return toView(saved);
    }

    @Transactional(readOnly = true)
    public ApprovalRequestEntity requireApprovedGeneratedTestExecution(
            UUID caseId,
            UUID evidenceId
    ) {
        return repository
                .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                        caseId,
                        ApprovalTargetType.GENERATED_TEST_EXECUTION,
                        evidenceId,
                        ApprovalStatus.APPROVED
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Approved generated test execution "
                                        + "was not found. caseId="
                                        + caseId
                                        + ", evidenceId="
                                        + evidenceId
                        )
                );
    }

    private EvidenceEntity latestGeneratedWriteResult(
            UUID caseId
    ) {
        return evidenceService.list(caseId)
                .stream()
                .filter(item ->
                        item.getEvidenceType()
                                == EvidenceType.GENERATED_TEST
                )
                .filter(item ->
                        GENERATED_WRITE_RESULT_SOURCE.equals(
                                item.getSource()
                        )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Approved generated test write result "
                                        + "was not found for case: "
                                        + caseId
                        )
                );
    }

    @Transactional
    public ApprovalRequestView createPatternInformedTestSourceApproval(
            UUID caseId,
            String actor,
            String comment
    ) {
        String normalizedActor =
                requireActor(actor);

        EvidenceEntity candidateEvidence =
                latestEvidence(
                        caseId,
                        EvidenceType.GENERATED_TEST,
                        PATTERN_CANDIDATE_SOURCE
                );

        PatternInformedTestSourceCandidate candidate =
                parseCandidate(candidateEvidence);

        if (candidate.readiness()
                != TestSourceReadiness.READY_FOR_REVIEW) {

            throw new IllegalStateException(
                    "Pattern-informed test source is not ready for approval. "
                            + "Current readiness: "
                            + candidate.readiness()
            );
        }

        if (candidate.source() == null
                || candidate.source().isBlank()) {

            throw new IllegalStateException(
                    "Pattern-informed candidate source is empty."
            );
        }

        var existingPending =
                repository
                        .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                                caseId,
                                ApprovalTargetType.PATTERN_INFORMED_TEST_SOURCE,
                                candidateEvidence.getId(),
                                ApprovalStatus.PENDING
                        );

        if (existingPending.isPresent()) {
            return toView(
                    existingPending.get()
            );
        }

        ApprovalRequestEntity entity =
                new ApprovalRequestEntity();

        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setTargetType(
                ApprovalTargetType.PATTERN_INFORMED_TEST_SOURCE
        );
        entity.setTargetEvidenceId(
                candidateEvidence.getId()
        );
        entity.setTargetEvidenceType(
                EvidenceType.GENERATED_TEST.name()
        );
        entity.setTargetEvidenceSource(
                PATTERN_CANDIDATE_SOURCE
        );
        entity.setStatus(
                ApprovalStatus.PENDING
        );
        entity.setRequestedBy(
                normalizedActor
        );
        entity.setRequestComment(
                normalizeComment(comment)
        );
        entity.setRequestedAt(
                Instant.now()
        );

        ApprovalRequestEntity saved =
                repository.save(entity);

        auditService.record(
                caseId,
                "PATTERN_INFORMED_TEST_SOURCE_APPROVAL_REQUESTED",
                normalizedActor,
                "approvalId="
                        + saved.getId()
                        + ", evidenceId="
                        + saved.getTargetEvidenceId()
                        + ", readiness="
                        + candidate.readiness()
                        + ", confidence="
                        + candidate.compileConfidence()
        );

        return toView(saved);
    }

    @Transactional(readOnly = true)
    public ApprovalRequestEntity requireApprovedPatternInformedTestSource(
            UUID caseId,
            UUID evidenceId
    ) {
        return repository
                .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                        caseId,
                        ApprovalTargetType.PATTERN_INFORMED_TEST_SOURCE,
                        evidenceId,
                        ApprovalStatus.APPROVED
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Approved pattern-informed test source "
                                        + "was not found. caseId="
                                        + caseId
                                        + ", evidenceId="
                                        + evidenceId
                        )
                );
    }

    private EvidenceEntity latestEvidence(
            UUID caseId,
            EvidenceType type,
            String source
    ) {
        return evidenceService.list(caseId)
                .stream()
                .filter(item ->
                        item.getEvidenceType() == type
                )
                .filter(item ->
                        source.equals(
                                item.getSource()
                        )
                )
                .reduce(
                        (first, second) -> second
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Evidence not found. type="
                                        + type
                                        + ", source="
                                        + source
                        )
                );
    }

    private PatternInformedTestSourceCandidate parseCandidate(
            EvidenceEntity evidence
    ) {
        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    PatternInformedTestSourceCandidate.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse pattern-informed candidate.",
                    exception
            );
        }
    }

    private String determineAuditAction(
            ApprovalTargetType targetType,
            ApprovalStatus decision
    ) {
        boolean approved = decision == ApprovalStatus.APPROVED;

        return switch (targetType) {
            case REGRESSION_TEST_PLAN ->
                    approved
                            ? "REGRESSION_TEST_PLAN_APPROVED"
                            : "REGRESSION_TEST_PLAN_REJECTED";
            case FAILING_REGRESSION_TEST_DRAFT ->
                    approved
                            ? "FAILING_REGRESSION_TEST_DRAFT_APPROVED"
                            : "FAILING_REGRESSION_TEST_DRAFT_REJECTED";
            case GENERATED_TEST_EXECUTION ->
                    approved
                            ? "GENERATED_TEST_EXECUTION_APPROVED"
                            : "GENERATED_TEST_EXECUTION_REJECTED";
            case PATTERN_INFORMED_TEST_SOURCE ->
                    approved
                            ? "PATTERN_INFORMED_TEST_SOURCE_APPROVED"
                            : "PATTERN_INFORMED_TEST_SOURCE_REJECTED";
            case JIRA_EVIDENCE_SUMMARY ->
                    approved
                            ? "JIRA_EVIDENCE_SUMMARY_APPROVED"
                            : "JIRA_EVIDENCE_SUMMARY_REJECTED";
            default ->
                    approved
                            ? "APPROVAL_GRANTED"
                            : "APPROVAL_REJECTED";
        };
    }

    @Transactional
    public ApprovalRequestView createJiraEvidenceSummaryApproval(
            UUID caseId,
            UUID previewEvidenceId,
            String actor,
            String comment
    ) {
        String normalizedActor = requireActor(actor);

        EvidenceEntity previewEvidence = evidenceService.find(caseId, previewEvidenceId);

        if (previewEvidence.getEvidenceType() != EvidenceType.REPLAY_OUTPUT) {
            throw new IllegalArgumentException("Evidence is not REPLAY_OUTPUT type");
        }

        if (!"jira-evidence-summary-preview".equals(previewEvidence.getSource())) {
            throw new IllegalArgumentException("Evidence source is not jira-evidence-summary-preview");
        }

        var existingPending = repository
                .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                        caseId,
                        ApprovalTargetType.JIRA_EVIDENCE_SUMMARY,
                        previewEvidenceId,
                        ApprovalStatus.PENDING
                );

        if (existingPending.isPresent()) {
            return toView(existingPending.get());
        }

        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setTargetType(ApprovalTargetType.JIRA_EVIDENCE_SUMMARY);
        entity.setTargetEvidenceId(previewEvidenceId);
        entity.setTargetEvidenceType(previewEvidence.getEvidenceType().name());
        entity.setTargetEvidenceSource(previewEvidence.getSource());
        entity.setRequestedBy(normalizedActor);
        entity.setRequestComment(comment);
        entity.setStatus(ApprovalStatus.PENDING);
        entity.setRequestedAt(Instant.now());

        entity = repository.save(entity);
        
        final ApprovalRequestEntity savedEntity = entity;

        auditService.record(
                caseId,
                "JIRA_EVIDENCE_SUMMARY_APPROVAL_REQUESTED",
                normalizedActor,
                "Approval requested for evidence preview " + previewEvidenceId
        );

        caseRepository.findById(caseId).ifPresent(caseEntity -> {
            notificationService.notifyApprovalRequested(savedEntity, caseEntity);
        });

        return toView(savedEntity);
    }

    @Transactional(readOnly = true)
    public ApprovalRequestEntity requireApprovedJiraEvidenceSummary(
            UUID caseId,
            UUID previewEvidenceId
    ) {
        return repository
                .findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
                        caseId,
                        ApprovalTargetType.JIRA_EVIDENCE_SUMMARY,
                        previewEvidenceId,
                        ApprovalStatus.APPROVED
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Approved Jira evidence summary not found. caseId="
                                        + caseId
                                        + ", previewEvidenceId="
                                        + previewEvidenceId
                        )
                );
    }
}
