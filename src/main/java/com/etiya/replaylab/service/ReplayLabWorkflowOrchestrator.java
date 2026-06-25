package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.*;
import com.etiya.replaylab.model.JiraWebhookEvent;
import com.etiya.replaylab.model.WorkflowRunView;
import com.etiya.replaylab.model.WorkflowStepView;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.WorkflowRunRepository;
import com.etiya.replaylab.repository.WorkflowStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReplayLabWorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReplayLabWorkflowOrchestrator.class);

    private final ReplayCaseRepository caseRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final AuditService auditService;
    private final ReplayLabNotificationService notificationService;

    public ReplayLabWorkflowOrchestrator(
            ReplayCaseRepository caseRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowStepRepository workflowStepRepository,
            AuditService auditService,
            ReplayLabNotificationService notificationService
    ) {
        this.caseRepository = caseRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public UUID startFromJiraWebhook(JiraWebhookEvent event) {
        UUID caseId = findOrCreateCase(event);

        boolean hasActiveRun = workflowRunRepository.existsByCaseIdAndStatusIn(
                caseId,
                List.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING)
        );

        if (hasActiveRun) {
            log.info("Case {} already has active workflow, skipping duplicate", caseId);
            Optional<WorkflowRunEntity> existing = workflowRunRepository
                    .findFirstByCaseIdAndStatusInOrderByCreatedAtDesc(
                            caseId,
                            List.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING)
                    );
            return existing.map(WorkflowRunEntity::getId).orElse(null);
        }

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setCaseId(caseId);
        run.setTriggerType("JIRA_WEBHOOK");
        run.setTriggerReference(event.issueKey());
        run.setStatus(WorkflowRunStatus.PENDING);

        run = workflowRunRepository.save(run);

        createPendingSteps(run.getId());

        auditService.record(
                caseId,
                "WORKFLOW_STARTED",
                "system",
                "Workflow " + run.getId() + " started from Jira webhook " + event.issueKey()
        );

        UUID runId = run.getId();
        executeWorkflowAsync(runId);

        return runId;
    }

    @Async("workflowExecutor")
    protected void executeWorkflowAsync(UUID runId) {
        log.info("Starting async execution of workflow {}", runId);

        try {
            WorkflowRunEntity run = workflowRunRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException("Workflow run not found"));

            run.setStatus(WorkflowRunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            workflowRunRepository.save(run);

            List<WorkflowStepEntity> steps = workflowStepRepository
                    .findByWorkflowRunIdOrderByStartedAtAscIdAsc(runId);

            for (WorkflowStepEntity step : steps) {
                executeStep(run, step);
            }

            finalizeWorkflowRun(run);

        } catch (Exception e) {
            log.error("Workflow {} execution failed", runId, e);
            failWorkflowRun(runId, e.getMessage());
        }
    }

    private void executeStep(WorkflowRunEntity run, WorkflowStepEntity step) {
        step.setStatus(WorkflowStepStatus.RUNNING);
        step.setStartedAt(Instant.now());
        workflowStepRepository.save(step);

        auditService.record(
                run.getCaseId(),
                "WORKFLOW_STEP_STARTED",
                "system",
                "Step " + step.getStepName() + " started"
        );

        try {
            boolean shouldSkip = shouldSkipStep(run.getCaseId(), step.getStepName());

            if (shouldSkip) {
                step.setStatus(WorkflowStepStatus.SKIPPED);
                step.setFinishedAt(Instant.now());
                step.setResultSummary("Step skipped due to configuration or prerequisites");
                workflowStepRepository.save(step);

                auditService.record(
                        run.getCaseId(),
                        "WORKFLOW_STEP_SKIPPED",
                        "system",
                        "Step " + step.getStepName() + " skipped"
                );
                return;
            }

            log.info("Executing workflow step: {}", step.getStepName());

            step.setStatus(WorkflowStepStatus.SUCCESS);
            step.setFinishedAt(Instant.now());
            step.setResultSummary("Step completed (POC: actual implementation pending)");
            workflowStepRepository.save(step);

            auditService.record(
                    run.getCaseId(),
                    "WORKFLOW_STEP_SUCCEEDED",
                    "system",
                    "Step " + step.getStepName() + " succeeded"
            );

        } catch (Exception e) {
            log.error("Step {} failed", step.getStepName(), e);

            String errorCategory = classifyError(e);
            boolean retryable = isRetryableError(errorCategory);

            step.setErrorCategory(errorCategory);
            step.setErrorMessage(sanitizeErrorMessage(e.getMessage()));

            if (retryable && step.getAttempt() < step.getMaxAttempts()) {
                step.setStatus(WorkflowStepStatus.RETRY_WAITING);
                step.setNextRetryAt(calculateNextRetry(step.getAttempt()));
                step.setAttempt(step.getAttempt() + 1);
            } else {
                step.setStatus(WorkflowStepStatus.FAILED);
                step.setFinishedAt(Instant.now());
            }

            workflowStepRepository.save(step);

            auditService.record(
                    run.getCaseId(),
                    "WORKFLOW_STEP_FAILED",
                    "system",
                    "Step " + step.getStepName() + " failed: " + errorCategory
            );
        }
    }

    private void finalizeWorkflowRun(WorkflowRunEntity run) {
        List<WorkflowStepEntity> steps = workflowStepRepository
                .findByWorkflowRunIdOrderByStartedAtAscIdAsc(run.getId());

        int successful = 0;
        int failed = 0;
        int skipped = 0;
        UUID jiraPreviewEvidenceId = null;

        for (WorkflowStepEntity step : steps) {
            if (step.getStatus() == WorkflowStepStatus.SUCCESS) successful++;
            else if (step.getStatus() == WorkflowStepStatus.FAILED) failed++;
            else if (step.getStatus() == WorkflowStepStatus.SKIPPED) skipped++;

            if ("CREATE_JIRA_EVIDENCE_PREVIEW".equals(step.getStepName()) &&
                    step.getStatus() == WorkflowStepStatus.SUCCESS &&
                    step.getEvidenceId() != null) {
                jiraPreviewEvidenceId = step.getEvidenceId();
            }
        }

        run.setSuccessfulStepCount(successful);
        run.setFailedStepCount(failed);
        run.setSkippedStepCount(skipped);
        run.setJiraPreviewEvidenceId(jiraPreviewEvidenceId);
        run.setFinishedAt(Instant.now());

        if (failed == 0) {
            run.setStatus(WorkflowRunStatus.SUCCESS);
            run.setSummary("All required steps completed successfully");
        } else if (successful > 0) {
            run.setStatus(WorkflowRunStatus.PARTIAL_SUCCESS);
            run.setSummary(String.format("%d steps succeeded, %d failed", successful, failed));
        } else {
            run.setStatus(WorkflowRunStatus.FAILED);
            run.setSummary("Workflow execution failed");
        }

        workflowRunRepository.save(run);

        if (jiraPreviewEvidenceId != null) {
            auditService.record(
                    run.getCaseId(),
                    "JIRA_EVIDENCE_PREVIEW_AUTO_CREATED",
                    "system",
                    "Jira evidence preview auto-created: " + jiraPreviewEvidenceId
            );
        }

        auditService.record(
                run.getCaseId(),
                "WORKFLOW_COMPLETED",
                "system",
                "Workflow completed with status: " + run.getStatus()
        );

        caseRepository.findById(run.getCaseId()).ifPresent(caseEntity -> {
            notificationService.notifyWorkflowCompleted(run, caseEntity);
        });
    }

    private void failWorkflowRun(UUID runId, String error) {
        workflowRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(WorkflowRunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setSummary("Workflow execution error: " + sanitizeErrorMessage(error));
            workflowRunRepository.save(run);
        });
    }

    @Transactional(readOnly = true)
    public WorkflowRunView getRun(UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run not found"));

        List<WorkflowStepEntity> steps = workflowStepRepository
                .findByWorkflowRunIdOrderByStartedAtAscIdAsc(runId);

        List<WorkflowStepView> stepViews = steps.stream()
                .map(this::toStepView)
                .toList();

        return new WorkflowRunView(
                run.getId(),
                run.getCaseId(),
                run.getTriggerType(),
                run.getTriggerReference(),
                run.getStatus().name(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getSuccessfulStepCount(),
                run.getFailedStepCount(),
                run.getSkippedStepCount(),
                run.getJiraPreviewEvidenceId(),
                run.getSummary(),
                stepViews
        );
    }

    @Transactional(readOnly = true)
    public List<WorkflowRunView> getCaseWorkflows(UUID caseId) {
        List<WorkflowRunEntity> runs = workflowRunRepository.findByCaseIdOrderByCreatedAtDesc(caseId);

        return runs.stream()
                .map(run -> getRun(run.getId()))
                .toList();
    }

    private UUID findOrCreateCase(JiraWebhookEvent event) {
        Optional<ReplayCaseEntity> existing = caseRepository.findFirstByJiraKey(event.issueKey());

        if (existing.isPresent()) {
            return existing.get().getId();
        }

        ReplayCaseEntity newCase = new ReplayCaseEntity();
        newCase.setJiraKey(event.issueKey());
        newCase.setTargetKey(event.issueKey());
        newCase.setStatus(ReplayCaseStatus.NEW);

        newCase = caseRepository.save(newCase);

        auditService.record(
                newCase.getId(),
                "CASE_CREATED_FROM_WEBHOOK",
                "system",
                "Case created from Jira webhook: " + event.issueKey()
        );

        return newCase.getId();
    }

    private void createPendingSteps(UUID runId) {
        String[] stepNames = {
                "COLLECT_JIRA",
                "PLAN_LOKI_QUERY",
                "COLLECT_LOKI",
                "COLLECT_TEMPO",
                "RESOLVE_REPOSITORY",
                "RESOLVE_INCIDENT_VERSION",
                "CHECKOUT_SOURCE",
                "COLLECT_SOURCE_CONTEXT",
                "COLLECT_JENKINS",
                "VALIDATE_JENKINS_VERSION",
                "COLLECT_CONFLUENCE",
                "COLLECT_KUBERNETES_RUNTIME",
                "CORRELATE_KUBERNETES_VERSION",
                "BUILD_AI_INPUT_BUNDLE",
                "BUILD_DETERMINISTIC_ROOT_CAUSE",
                "CREATE_JIRA_EVIDENCE_PREVIEW"
        };

        for (String stepName : stepNames) {
            WorkflowStepEntity step = new WorkflowStepEntity();
            step.setWorkflowRunId(runId);
            step.setStepName(stepName);
            step.setStatus(WorkflowStepStatus.PENDING);
            step.setMaxAttempts(3);
            workflowStepRepository.save(step);
        }
    }

    private boolean shouldSkipStep(UUID caseId, String stepName) {
        return false;
    }

    private String classifyError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (message.contains("401") || message.contains("unauthorized")) {
            return "AUTHENTICATION_ERROR";
        }
        if (message.contains("403") || message.contains("forbidden")) {
            return "AUTHORIZATION_ERROR";
        }
        if (message.contains("429")) {
            return "RATE_LIMIT";
        }
        if (message.contains("502") || message.contains("503") || message.contains("504")) {
            return "UPSTREAM_UNAVAILABLE";
        }
        if (message.contains("timeout")) {
            return "TIMEOUT";
        }

        return "UNKNOWN_ERROR";
    }

    private boolean isRetryableError(String errorCategory) {
        return List.of("RATE_LIMIT", "UPSTREAM_UNAVAILABLE", "TIMEOUT")
                .contains(errorCategory);
    }

    private Instant calculateNextRetry(int attempt) {
        int[] delays = {30, 120, 600};
        int delaySeconds = attempt < delays.length ? delays[attempt] : 600;
        return Instant.now().plusSeconds(delaySeconds);
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null) return null;
        if (message.length() > 500) {
            return message.substring(0, 500) + "...";
        }
        return message;
    }

    private WorkflowStepView toStepView(WorkflowStepEntity step) {
        return new WorkflowStepView(
                step.getId(),
                step.getStepName(),
                step.getStatus().name(),
                step.getAttempt(),
                step.getMaxAttempts(),
                step.getStartedAt(),
                step.getFinishedAt(),
                step.getNextRetryAt(),
                step.getEvidenceType(),
                step.getEvidenceSource(),
                step.getErrorCategory(),
                step.getErrorMessage(),
                step.getResultSummary()
        );
    }
}
