package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.CreateCaseRequest;
import com.etiya.replayfix.domain.*;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Golden Path Orchestration Service for demo purposes.
 * Orchestrates end-to-end evidence collection for real incidents WITHOUT any write operations to external systems.
 */
@Service
public class GoldenPathOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(GoldenPathOrchestrationService.class);

    private final ReplayCaseService caseService;
    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JiraEvidenceCollectionService jiraEvidenceService;
    private final JenkinsEvidenceCollectorService jenkinsEvidenceService;
    private final DeterministicRootCauseRefreshService deterministicRcaService;

    public GoldenPathOrchestrationService(
            ReplayCaseService caseService,
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JiraEvidenceCollectionService jiraEvidenceService,
            JenkinsEvidenceCollectorService jenkinsEvidenceService,
            DeterministicRootCauseRefreshService deterministicRcaService
    ) {
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.jiraEvidenceService = jiraEvidenceService;
        this.jenkinsEvidenceService = jenkinsEvidenceService;
        this.deterministicRcaService = deterministicRcaService;
    }

    public Map<String, Object> executeGoldenPath(String jiraKey, String targetKey) {
        log.info("GOLDEN_PATH_START: jiraKey={}, targetKey={}", jiraKey, targetKey);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jiraKey", jiraKey);
        result.put("targetKey", targetKey);
        result.put("timestamp", Instant.now());
        
        Map<String, Object> steps = new LinkedHashMap<>();
        result.put("steps", steps);

        try {
            // Step 1: Find or create case
            steps.put("1_case_resolution", findOrCreateCase(jiraKey, targetKey, result));

            UUID caseId = (UUID) result.get("caseId");

            // Step 2: Collect Jira evidence
            steps.put("2_jira_evidence", collectJiraEvidence(caseId, jiraKey));

            // Step 3: Collect Jenkins evidence
            steps.put("3_jenkins_evidence", collectJenkinsEvidence(caseId));

            // Step 4: Collect Loki evidence (through existing services)
            steps.put("4_loki_evidence", collectLokiEvidence(caseId));

            // Step 5: Collect Tempo evidence (through existing services)
            steps.put("5_tempo_evidence", collectTempoEvidence(caseId));

            // Step 6: Generate deterministic RCA
            steps.put("6_deterministic_rca", generateDeterministicRca(caseId));

            // Step 7: Summary
            steps.put("7_summary", buildSummary(caseId));

            result.put("status", "SUCCESS");
            result.put("synthetic", false);
            
        } catch (Exception e) {
            log.error("GOLDEN_PATH_FAILED: jiraKey={}", jiraKey, e);
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
        }

        log.info("GOLDEN_PATH_COMPLETE: jiraKey={}, status={}", jiraKey, result.get("status"));
        return result;
    }

    private Map<String, Object> findOrCreateCase(String jiraKey, String targetKey, Map<String, Object> context) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            // First try to find existing case
            Optional<ReplayCaseEntity> existing = caseRepository.findFirstByJiraKey(jiraKey);
            
            if (existing.isPresent()) {
                ReplayCaseEntity caseEntity = existing.get();
                step.put("action", "FOUND_EXISTING");
                step.put("caseId", caseEntity.getId());
                step.put("status", caseEntity.getStatus());
                step.put("createdAt", caseEntity.getCreatedAt());
                context.put("caseId", caseEntity.getId());
                context.put("caseExists", true);
            } else {
                // Create new case
                CreateCaseRequest request = new CreateCaseRequest(
                        jiraKey,
                        targetKey,
                        null, // orderId
                        null, // traceId
                        null, // incidentTime
                        null, // sourceBranch
                        null, // sourceCommit
                        null  // imageTag
                );
                
                ReplayCaseEntity newCase = caseService.create(request);
                step.put("action", "CREATED_NEW");
                step.put("caseId", newCase.getId());
                step.put("status", newCase.getStatus());
                step.put("createdAt", newCase.getCreatedAt());
                context.put("caseId", newCase.getId());
                context.put("caseExists", false);
            }
            
            step.put("result", "SUCCESS");
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            throw new RuntimeException("Case resolution failed", e);
        }
        
        return step;
    }

    private Map<String, Object> collectJiraEvidence(UUID caseId, String jiraKey) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            jiraEvidenceService.collectJiraEvidence(caseId, jiraKey);
            
            List<EvidenceEntity> jiraEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE)
                    .toList();
            
            step.put("result", "SUCCESS");
            step.put("count", jiraEvidence.size());
            step.put("evidenceIds", jiraEvidence.stream().map(EvidenceEntity::getId).toList());
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.warn("Jira evidence collection failed for caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> collectJenkinsEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            jenkinsEvidenceService.collect(caseId);
            
            List<EvidenceEntity> jenkinsEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.JENKINS_BUILD || 
                                 e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT)
                    .toList();
            
            step.put("result", "SUCCESS");
            step.put("count", jenkinsEvidence.size());
            step.put("evidenceIds", jenkinsEvidence.stream().map(EvidenceEntity::getId).toList());
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.warn("Jenkins evidence collection failed for caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> collectLokiEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            // Loki evidence collection happens through existing orchestrator
            // Check if Loki evidence already exists
            List<EvidenceEntity> lokiEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.LOKI_LOGS)
                    .toList();
            
            if (lokiEvidence.isEmpty()) {
                step.put("result", "UNAVAILABLE");
                step.put("reason", "Loki evidence not collected yet - requires orchestrator.collectContext()");
            } else {
                step.put("result", "FOUND_EXISTING");
                step.put("count", lokiEvidence.size());
                step.put("evidenceIds", lokiEvidence.stream().map(EvidenceEntity::getId).toList());
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.warn("Loki evidence check failed for caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> collectTempoEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            // Tempo evidence collection happens through existing orchestrator
            // Check if Tempo evidence already exists
            List<EvidenceEntity> tempoEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.TEMPO_TRACE)
                    .toList();
            
            if (tempoEvidence.isEmpty()) {
                step.put("result", "UNAVAILABLE");
                step.put("reason", "Tempo evidence not collected yet - requires orchestrator.collectContext()");
            } else {
                step.put("result", "FOUND_EXISTING");
                step.put("count", tempoEvidence.size());
                step.put("evidenceIds", tempoEvidence.stream().map(EvidenceEntity::getId).toList());
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.warn("Tempo evidence check failed for caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> generateDeterministicRca(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            deterministicRcaService.refresh(caseId);
            
            List<EvidenceEntity> rcaEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.ROOT_CAUSE_ANALYSIS)
                    .toList();
            
            if (rcaEvidence.isEmpty()) {
                step.put("result", "NOT_GENERATED");
                step.put("reason", "Insufficient evidence or RCA generation failed");
            } else {
                EvidenceEntity rca = rcaEvidence.get(0);
                step.put("result", "SUCCESS");
                step.put("evidenceId", rca.getId());
                step.put("confidence", rca.getConfidence());
                step.put("source", rca.getSource());
                step.put("synthetic", false);
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.warn("Deterministic RCA generation failed for caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> buildSummary(UUID caseId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        
        try {
            ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found"));
            
            List<EvidenceEntity> allEvidence = evidenceRepository.findByCaseId(caseId);
            
            Map<String, Long> evidenceByType = allEvidence.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            e -> e.getEvidenceType().name(),
                            java.util.stream.Collectors.counting()
                    ));
            
            summary.put("caseId", caseId);
            summary.put("jiraKey", caseEntity.getJiraKey());
            summary.put("status", caseEntity.getStatus());
            summary.put("totalEvidence", allEvidence.size());
            summary.put("evidenceByType", evidenceByType);
            summary.put("synthetic", false);
            summary.put("dashboardUrl", "/replayfix/?caseId=" + caseId);
            
            // Check for missing evidence
            List<String> missingEvidence = new ArrayList<>();
            if (!evidenceByType.containsKey("JIRA_ISSUE")) {
                missingEvidence.add("JIRA_ISSUE");
            }
            if (!evidenceByType.containsKey("JENKINS_BUILD")) {
                missingEvidence.add("JENKINS_BUILD");
            }
            if (!evidenceByType.containsKey("LOKI_LOGS")) {
                missingEvidence.add("LOKI_LOGS");
            }
            if (!evidenceByType.containsKey("TEMPO_TRACE")) {
                missingEvidence.add("TEMPO_TRACE (optional)");
            }
            if (!evidenceByType.containsKey("ROOT_CAUSE_ANALYSIS")) {
                missingEvidence.add("ROOT_CAUSE_ANALYSIS");
            }
            
            summary.put("missingEvidence", missingEvidence);
            
        } catch (Exception e) {
            summary.put("error", e.getMessage());
        }
        
        return summary;
    }
}
