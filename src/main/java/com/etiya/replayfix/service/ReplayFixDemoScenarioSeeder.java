package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.*;
import com.etiya.replayfix.model.DemoScenarioResult;
import com.etiya.replayfix.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("demo")
@ConditionalOnProperty(
        prefix = "replayfix.demo",
        name = "enabled",
        havingValue = "true"
)
public class ReplayFixDemoScenarioSeeder {

    private static final Logger log = LoggerFactory.getLogger(ReplayFixDemoScenarioSeeder.class);

    private final ReplayCaseRepository caseRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final EvidenceRepository evidenceRepository;
    private final AuditEventRepository auditEventRepository;
    private final ReplayFixProperties properties;

    public ReplayFixDemoScenarioSeeder(
            ReplayCaseRepository caseRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowStepRepository workflowStepRepository,
            EvidenceRepository evidenceRepository,
            AuditEventRepository auditEventRepository,
            ReplayFixProperties properties
    ) {
        this.caseRepository = caseRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.evidenceRepository = evidenceRepository;
        this.auditEventRepository = auditEventRepository;
        this.properties = properties;
    }

    @Transactional
    public DemoScenarioResult seedHttp401Scenario() {
        log.info("Creating demo scenario: HTTP 401 Unauthorized");

        List<String> warnings = new ArrayList<>();

        String demoTargetKey = resolveDemoTargetKey();
        
        ReplayCaseEntity caseEntity = new ReplayCaseEntity();
        caseEntity.setJiraKey("FIZZMS-DEMO-401");
        caseEntity.setTargetKey(demoTargetKey);
        caseEntity.setApplicationName("fizzms-api");
        caseEntity.setEnvironment("production");
        caseEntity.setIncidentTime(Instant.now().minusSeconds(3600));
        caseEntity.setSynthetic(true);
        caseEntity = caseRepository.save(caseEntity);

        auditEvent(caseEntity.getId(), "DEMO_CASE_CREATED", "demo-seeder", "Demo case created for HTTP 401 scenario");

        WorkflowRunEntity workflowRun = new WorkflowRunEntity();
        workflowRun.setCaseId(caseEntity.getId());
        workflowRun.setTriggerType("DEMO_TRIGGER");
        workflowRun.setTriggerReference("demo-http-401");
        workflowRun.setStatus(WorkflowRunStatus.RUNNING);
        workflowRun.setStartedAt(Instant.now());
        workflowRun = workflowRunRepository.save(workflowRun);

        UUID workflowRunId = workflowRun.getId();
        int stepSequence = 1;
        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        // Step 1: Collect Jira Issue
        createDemoStep(workflowRunId, stepSequence++, "COLLECT_JIRA", WorkflowStepStatus.SUCCESS, "JIRA_ISSUE", "jira-issue-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.JIRA_ISSUE, "jira-issue-demo", 
                "{\"key\":\"FIZZMS-DEMO-401\",\"summary\":\"HTTP 401 Unauthorized in user authentication\",\"status\":\"Open\",\"priority\":\"High\"}");
        successCount++;

        // Step 2: Repository Resolution (Bitbucket)
        createDemoStep(workflowRunId, stepSequence++, "REPOSITORY_RESOLUTION", WorkflowStepStatus.SUCCESS, "REPOSITORY_RESOLUTION", "bitbucket-repository-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.REPOSITORY_RESOLUTION, "bitbucket-repository-demo",
                "{\"repository\":\"fizzms-api\",\"branch\":\"main\",\"commit\":\"a1b2c3d4\",\"resolved\":true}");
        successCount++;

        // Step 3: Collect Loki Logs
        createDemoStep(workflowRunId, stepSequence++, "COLLECT_LOKI_LOGS", WorkflowStepStatus.SUCCESS, "LOKI_LOGS", "loki-logs-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.LOKI_LOGS, "loki-logs-demo",
                "{\"logs\":[\"2026-06-16T14:30:00Z ERROR: Authentication failed with status 401\",\"2026-06-16T14:30:01Z WARN: Token expired for user service\",\"2026-06-16T14:30:02Z INFO: Retry attempt initiated\"]}");
        successCount++;

        // Step 4: Collect Tempo Traces
        createDemoStep(workflowRunId, stepSequence++, "COLLECT_TEMPO_TRACES", WorkflowStepStatus.SUCCESS, "TEMPO_TRACE", "tempo-trace-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.TEMPO_TRACE, "tempo-trace-demo",
                "{\"traceId\":\"abc123def456\",\"serviceName\":\"fizzms-api\",\"spans\":[{\"name\":\"authenticate\",\"duration\":\"150ms\",\"status\":\"error\",\"statusCode\":401}]}");
        successCount++;

        // Step 5: Collect Jenkins Build Context
        createDemoStep(workflowRunId, stepSequence++, "COLLECT_JENKINS_BUILD_CONTEXT", WorkflowStepStatus.SUCCESS, "JENKINS_BUILD_CONTEXT", "jenkins-build-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.JENKINS_BUILD_CONTEXT, "jenkins-build-demo",
                "{\"buildNumber\":\"#1234\",\"jobName\":\"fizzms-api-deploy\",\"result\":\"SUCCESS\",\"timestamp\":\"2026-06-16T12:00:00Z\",\"artifacts\":[\"fizzms-api-1.2.3.jar\"]}");
        successCount++;

        // Step 6: Collect Confluence Knowledge
        createDemoStep(workflowRunId, stepSequence++, "COLLECT_CONFLUENCE_KNOWLEDGE", WorkflowStepStatus.SUCCESS, "REPLAY_OUTPUT", "confluence-knowledge-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.REPLAY_OUTPUT, "confluence-knowledge-demo",
                "{\"pages\":[{\"title\":\"Authentication Service Guide\",\"space\":\"TECH\",\"url\":\"https://confluence.example.com/pages/123\"}],\"relevance\":\"high\"}");
        successCount++;

        // Step 7: Collect Kubernetes Runtime (SKIPPED - service unavailable)
        createDemoStep(workflowRunId, stepSequence++, "COLLECT_KUBERNETES_RUNTIME", WorkflowStepStatus.SKIPPED, null, null);
        createDemoEvidence(caseEntity.getId(), EvidenceType.REPLAY_OUTPUT, "kubernetes-runtime-unavailable-demo",
                "{\"status\":\"UNAVAILABLE\",\"reason\":\"Kubernetes API unreachable in demo mode\",\"message\":\"Runtime context collection skipped for synthetic demo scenario\"}");
        skippedCount++;
        warnings.add("Kubernetes runtime evidence unavailable in demo mode");

        // Step 8: Analyze Root Cause
        createDemoStep(workflowRunId, stepSequence++, "ANALYZE_ROOT_CAUSE", WorkflowStepStatus.SUCCESS, "ROOT_CAUSE_ANALYSIS", "root-cause-demo");
        createDemoEvidence(caseEntity.getId(), EvidenceType.ROOT_CAUSE_ANALYSIS, "root-cause-demo",
                "{\"rootCause\":\"Expired JWT token not refreshed before API call\",\"confidence\":0.85,\"evidenceSources\":[\"loki-logs\",\"tempo-trace\"],\"recommendation\":\"Implement token refresh mechanism\"}");
        successCount++;

        // Step 9: Create Jira Evidence Preview
        createDemoStep(workflowRunId, stepSequence++, "CREATE_JIRA_EVIDENCE_PREVIEW", WorkflowStepStatus.SUCCESS, "REPLAY_OUTPUT", "jira-preview-demo");
        EvidenceEntity previewEvidence = createDemoEvidence(caseEntity.getId(), EvidenceType.REPLAY_OUTPUT, "jira-evidence-summary-preview",
                "{\"summary\":\"HTTP 401 Unauthorized caused by expired JWT token. Root cause: Token refresh mechanism missing. Recommendation: Implement token auto-refresh.\",\"confidence\":0.85}");
        successCount++;

        workflowRun.setJiraPreviewEvidenceId(previewEvidence.getId());
        workflowRun.setStatus(WorkflowRunStatus.PARTIAL_SUCCESS);
        workflowRun.setFinishedAt(Instant.now());
        workflowRun.setSuccessfulStepCount(successCount);
        workflowRun.setFailedStepCount(failedCount);
        workflowRun.setSkippedStepCount(skippedCount);
        workflowRun.setSummary(String.format("Demo workflow completed: %d successful, %d failed, %d skipped. Kubernetes runtime unavailable.", 
                successCount, failedCount, skippedCount));
        workflowRunRepository.save(workflowRun);

        auditEvent(caseEntity.getId(), "DEMO_WORKFLOW_COMPLETED", "demo-seeder", "Demo workflow completed");

        String dashboardUrl = "/replayfix/?caseId=" + caseEntity.getId() + "&presentation=true";

        log.info("Demo scenario HTTP 401 created: caseId={}, workflowRunId={}", caseEntity.getId(), workflowRunId);

        return new DemoScenarioResult(
                caseEntity.getId(),
                workflowRunId,
                caseEntity.getJiraKey(),
                dashboardUrl,
                true,
                "PARTIAL_SUCCESS",
                warnings
        );
    }

    @Transactional
    public void deleteScenario(UUID caseId) {
        log.info("Deleting demo scenario: caseId={}", caseId);

        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (!caseEntity.isSynthetic()) {
            throw new IllegalArgumentException("Cannot delete non-synthetic case: " + caseId);
        }

        evidenceRepository.deleteByCaseId(caseId);
        auditEventRepository.deleteByCaseId(caseId);

        List<WorkflowRunEntity> workflowRuns = workflowRunRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
        for (WorkflowRunEntity run : workflowRuns) {
            workflowStepRepository.deleteByWorkflowRunId(run.getId());
        }
        workflowRunRepository.deleteByCaseId(caseId);

        caseRepository.delete(caseEntity);

        log.info("Demo scenario deleted: caseId={}", caseId);
    }

    private void createDemoStep(UUID workflowRunId, int sequenceNumber, String stepName, WorkflowStepStatus status, String evidenceType, String evidenceSource) {
        WorkflowStepEntity step = new WorkflowStepEntity();
        step.setWorkflowRunId(workflowRunId);
        step.setSequenceNumber(sequenceNumber);
        step.setStepName(stepName);
        step.setStatus(status);
        step.setAttempt(1);
        step.setMaxAttempts(3);
        step.setStartedAt(Instant.now().minusSeconds(300 - (sequenceNumber * 20)));
        step.setFinishedAt(status == WorkflowStepStatus.SKIPPED ? null : Instant.now().minusSeconds(280 - (sequenceNumber * 20)));
        step.setEvidenceType(evidenceType);
        step.setEvidenceSource(evidenceSource);
        
        if (status == WorkflowStepStatus.SKIPPED) {
            step.setResultSummary("Demo step skipped - service unavailable in demo mode");
        } else {
            step.setResultSummary("Demo step completed successfully - synthetic data");
        }
        
        workflowStepRepository.save(step);
    }

    private EvidenceEntity createDemoEvidence(UUID caseId, EvidenceType evidenceType, String source, String content) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(evidenceType);
        evidence.setSource(source);
        evidence.setContentText(content);
        evidence.setBody(content);
        evidence.setSanitized(true);
        evidence.setConfidence(0.85);
        return evidenceRepository.save(evidence);
    }

    private void auditEvent(UUID caseId, String action, String actor, String details) {
        AuditEventEntity audit = new AuditEventEntity();
        audit.setCaseId(caseId);
        audit.setAction(action);
        audit.setActor(actor);
        audit.setDetails(details);
        auditEventRepository.save(audit);
    }

    private String resolveDemoTargetKey() {
        Map<String, ReplayFixProperties.Target> targets = properties.getTargets();
        
        if (targets == null || targets.isEmpty()) {
            String errorMsg = "Demo scenario requires at least one configured target in replayfix.targets. " +
                    "Please configure a target in application.yml or use a configuration profile that includes target definitions.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        String firstTargetKey = targets.keySet().iterator().next();
        log.info("Using target '{}' for demo scenario", firstTargetKey);
        return firstTargetKey;
    }
}
