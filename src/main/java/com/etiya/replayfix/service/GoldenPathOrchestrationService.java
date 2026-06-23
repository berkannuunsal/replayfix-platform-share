package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.CreateCaseRequest;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.*;
import com.etiya.replayfix.model.*;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public static final String INCIDENT_VERSION_EVIDENCE_MISSING =
            "INCIDENT_VERSION_EVIDENCE_MISSING";

    private final ReplayCaseService caseService;
    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JiraEvidenceCollectionService jiraEvidenceService;
    private final RepositoryResolutionEvidenceService repositoryResolutionService;
    private final JenkinsEvidenceCollectorService jenkinsEvidenceService;
    private final ReplayOrchestrator replayOrchestrator;
    private final AiInputBundleRefreshService aiInputBundleRefreshService;
    private final DeterministicRootCauseRefreshService deterministicRcaService;
    private final ReplayFixProperties properties;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public GoldenPathOrchestrationService(
            ReplayCaseService caseService,
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JiraEvidenceCollectionService jiraEvidenceService,
            RepositoryResolutionEvidenceService repositoryResolutionService,
            JenkinsEvidenceCollectorService jenkinsEvidenceService,
            ReplayOrchestrator replayOrchestrator,
            AiInputBundleRefreshService aiInputBundleRefreshService,
            DeterministicRootCauseRefreshService deterministicRcaService,
            ReplayFixProperties properties,
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.jiraEvidenceService = jiraEvidenceService;
        this.repositoryResolutionService = repositoryResolutionService;
        this.jenkinsEvidenceService = jenkinsEvidenceService;
        this.replayOrchestrator = replayOrchestrator;
        this.aiInputBundleRefreshService = aiInputBundleRefreshService;
        this.deterministicRcaService = deterministicRcaService;
        this.properties = properties;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> executeGoldenPath(String jiraKey, String targetKey, boolean forceNew) {
        return executeGoldenPath(jiraKey, targetKey, forceNew, true);
    }

    public Map<String, Object> executeGoldenPath(
            String jiraKey,
            String targetKey,
            boolean forceNew,
            boolean includeAiInputBundle
    ) {
        log.info(
                "GOLDEN_PATH_START: jiraKey={}, targetKey={}, forceNew={}, includeAiInputBundle={}",
                jiraKey,
                targetKey,
                forceNew,
                includeAiInputBundle
        );
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jiraKey", jiraKey);
        result.put("requestedTargetKey", targetKey);
        result.put("timestamp", Instant.now());
        result.put("forceNew", forceNew);
        result.put("includeAiInputBundle", includeAiInputBundle);
        List<String> warnings = new ArrayList<>();
        result.put("warnings", warnings);
        
        Map<String, Object> steps = new LinkedHashMap<>();
        result.put("steps", steps);

        try {
            // Step 0: Validate target exists in configuration
            Map<String, Object> targetValidation = validateTarget(targetKey);
            steps.put("0_target_validation", targetValidation);
            if ("FAILED".equals(targetValidation.get("result"))) {
                result.put("status", "FAILED");
                result.put("failureReason", "TARGET_NOT_CONFIGURED");
                log.error("GOLDEN_PATH_FAILED: targetKey={} not configured", targetKey);
                return result;
            }
            
            // Step 1: Find or create case
            steps.put("1_case_resolution", findOrCreateCase(jiraKey, targetKey, forceNew, result));

            UUID caseId = (UUID) result.get("caseId");

            // Step 2: Collect Jira evidence
            steps.put("2_jira_evidence", collectJiraEvidence(caseId, jiraKey));

            // Step 3: Repository resolution
            Map<String, Object> repoResolution = collectRepositoryResolution(caseId, jiraKey, targetKey);
            steps.put("3_repository_resolution", repoResolution);
            boolean repositoryResolved = "SUCCESS".equals(repoResolution.get("result"));

            // Step 4: Collect Jenkins evidence (only if repository resolved)
            boolean jenkinsCollected = false;
            if (repositoryResolved) {
                Map<String, Object> jenkinsStep = collectJenkinsEvidence(caseId);
                steps.put("4_jenkins_evidence", jenkinsStep);
                jenkinsCollected = "SUCCESS".equals(jenkinsStep.get("result"));
            } else {
                Map<String, Object> jenkinsSkipped = new LinkedHashMap<>();
                jenkinsSkipped.put("result", "SKIPPED");
                jenkinsSkipped.put("reason", "Repository resolution failed: " + repoResolution.get("error"));
                steps.put("4_jenkins_evidence", jenkinsSkipped);
                log.warn("JENKINS_SKIPPED: Repository resolution failed for caseId={}", caseId);
            }

            // Step 5: Create incident version (only if Jenkins succeeded)
            boolean incidentVersionCreated = false;
            if (jenkinsCollected) {
                Map<String, Object> incidentVersionStep = collectIncidentVersion(caseId);
                steps.put("5_incident_version", incidentVersionStep);
                incidentVersionCreated = "SUCCESS".equals(incidentVersionStep.get("result"));
            } else {
                Map<String, Object> incidentVersionSkipped = new LinkedHashMap<>();
                incidentVersionSkipped.put("result", "SKIPPED");
                incidentVersionSkipped.put("reason", "Jenkins evidence collection failed");
                steps.put("5_incident_version", incidentVersionSkipped);
                log.warn("INCIDENT_VERSION_SKIPPED: Jenkins collection failed for caseId={}", caseId);
            }

            // Step 6: Context collection (Loki, Tempo, Source context) - only if Jenkins succeeded
            if (jenkinsCollected) {
                steps.put("6_context_collection", collectContextEvidence(caseId));
            } else {
                Map<String, Object> contextSkipped = new LinkedHashMap<>();
                contextSkipped.put("result", "SKIPPED");
                contextSkipped.put("reason", "Jenkins evidence collection failed");
                steps.put("6_context_collection", contextSkipped);
            }

            // Step 7-9: Report Loki, Tempo, AI bundle from evidence
            steps.put("7_loki_evidence", reportLokiEvidence(caseId));
            steps.put("8_tempo_evidence", reportTempoEvidence(caseId));
            if (includeAiInputBundle) {
                Map<String, Object> aiInputBundleStep =
                        collectAiInputBundle(caseId);
                steps.put("9_ai_input_bundle", aiInputBundleStep);
                mergeStepWarnings(aiInputBundleStep, warnings);
            } else {
                Map<String, Object> aiInputBundleSkipped =
                        new LinkedHashMap<>();
                aiInputBundleSkipped.put("result", "SKIPPED");
                aiInputBundleSkipped.put(
                        "reason",
                        "includeAiInputBundle=false"
                );
                aiInputBundleSkipped.put("bundleCreated", false);
                steps.put("9_ai_input_bundle", aiInputBundleSkipped);
                log.info(
                        "AI_INPUT_BUNDLE_SKIPPED: caseId={} reason=includeAiInputBundle=false",
                        caseId
                );
            }

            // Step 10: Generate deterministic RCA (only if incident version exists)
            steps.put("10_deterministic_rca", generateDeterministicRca(caseId));

            // Step 11: Summary
            steps.put("11_summary", buildSummary(caseId, steps));

            // Determine overall status based on step results
            String overallStatus = calculateOverallStatus(steps);
            if (hasMissingIncidentVersion(steps)) {
                if (!warnings.contains(INCIDENT_VERSION_EVIDENCE_MISSING)) {
                    warnings.add(INCIDENT_VERSION_EVIDENCE_MISSING);
                }
                if (!"FAILED".equals(overallStatus)) {
                    overallStatus = "NEEDS_EVIDENCE";
                }
            }
            if (warnings.contains(INCIDENT_VERSION_EVIDENCE_MISSING)
                    && !"FAILED".equals(overallStatus)) {
                overallStatus = "NEEDS_EVIDENCE";
            }
            
            result.put("status", overallStatus);
            result.put("synthetic", false);
            result.put("caseId", result.get("caseId"));
            result.put("reusedExistingCase", result.get("reusedExistingCase"));
            result.put("resolvedTargetKey", result.get("resolvedTargetKey"));
            
        } catch (Exception e) {
            log.error("GOLDEN_PATH_FAILED: jiraKey={}", jiraKey, e);
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
        }

        log.info("GOLDEN_PATH_COMPLETE: jiraKey={}, status={}, caseId={}", jiraKey, result.get("status"), result.get("caseId"));
        return result;
    }

    private Map<String, Object> validateTarget(String targetKey) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            Map<String, ReplayFixProperties.Target> targets = properties.getTargets();
            
            if (targets.containsKey(targetKey)) {
                step.put("result", "SUCCESS");
                step.put("targetKey", targetKey);
                step.put("targetExists", true);
                log.info("TARGET_VALIDATED: targetKey={}", targetKey);
            } else {
                step.put("result", "FAILED");
                step.put("targetKey", targetKey);
                step.put("targetExists", false);
                step.put("availableTargets", new ArrayList<>(targets.keySet()));
                log.error("TARGET_NOT_CONFIGURED: targetKey={}, available={}", targetKey, targets.keySet());
            }
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("TARGET_VALIDATION_ERROR: targetKey={}", targetKey, e);
        }
        
        return step;
    }

    private Map<String, Object> findOrCreateCase(String jiraKey, String targetKey, boolean forceNew, Map<String, Object> context) {
        Map<String, Object> step = new LinkedHashMap<>();
        boolean reusedExistingCase = false;
        
        try {
            ReplayCaseEntity caseEntity = null;
            
            if (!forceNew) {
                // Try to find existing case with matching jiraKey AND targetKey
                Optional<ReplayCaseEntity> existing = caseRepository.findFirstByJiraKeyAndTargetKey(jiraKey, targetKey);
                
                if (existing.isPresent()) {
                    caseEntity = existing.get();
                    reusedExistingCase = true;
                    step.put("action", "FOUND_EXISTING");
                    log.info("CASE_REUSED: jiraKey={}, targetKey={}, caseId={}", jiraKey, targetKey, caseEntity.getId());
                }
            }
            
            if (caseEntity == null) {
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
                
                caseEntity = caseService.create(request);
                
                // Ensure synthetic=false and Jenkins fields are null
                caseEntity.setSynthetic(false);
                caseEntity.setJenkinsJobName(null);
                caseEntity.setJenkinsBuildNumber(null);
                caseRepository.save(caseEntity);
                
                reusedExistingCase = false;
                step.put("action", forceNew ? "FORCED_NEW" : "CREATED_NEW");
                log.info("CASE_CREATED: jiraKey={}, targetKey={}, caseId={}, forceNew={}", 
                        jiraKey, targetKey, caseEntity.getId(), forceNew);
            }
            
            step.put("caseId", caseEntity.getId());
            step.put("status", caseEntity.getStatus());
            step.put("targetKey", caseEntity.getTargetKey());
            step.put("synthetic", caseEntity.isSynthetic());
            step.put("createdAt", caseEntity.getCreatedAt());
            step.put("reusedExistingCase", reusedExistingCase);
            
            context.put("caseId", caseEntity.getId());
            context.put("resolvedTargetKey", caseEntity.getTargetKey());
            context.put("reusedExistingCase", reusedExistingCase);
            
            step.put("result", "SUCCESS");
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("CASE_RESOLUTION_FAILED: jiraKey={}, targetKey={}", jiraKey, targetKey, e);
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

    private Map<String, Object> collectRepositoryResolution(UUID caseId, String jiraKey, String targetKey) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            EvidenceEntity evidence = repositoryResolutionService.collectRepositoryResolution(caseId, jiraKey, targetKey);
            
            // Parse evidence content to extract fields
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> evidenceContent = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(evidence.getContentText(), Map.class);
                
                step.put("result", "SUCCESS");
                step.put("evidenceId", evidence.getId());
                step.put("confidence", evidence.getConfidence());
                step.put("targetKey", targetKey);
                step.put("projectKey", evidenceContent.get("projectKey"));
                step.put("repositorySlug", evidenceContent.get("repositorySlug"));
                step.put("primaryRepositorySlug", evidenceContent.get("primaryRepositorySlug"));
                step.put("sourceBranch", evidenceContent.get("sourceBranch"));
                step.put("repositoryState", evidenceContent.get("repositoryState"));
                step.put("synthetic", false);
                
                log.info("REPOSITORY_RESOLUTION_SUCCESS: caseId={}, evidenceId={}, targetKey={}, projectKey={}, repositorySlug={}", 
                        caseId, evidence.getId(), targetKey, evidenceContent.get("projectKey"), evidenceContent.get("repositorySlug"));
            } catch (Exception parseEx) {
                step.put("result", "SUCCESS");
                step.put("evidenceId", evidence.getId());
                step.put("confidence", evidence.getConfidence());
                step.put("targetKey", targetKey);
                step.put("synthetic", false);
                log.warn("Repository resolution succeeded but could not parse evidence details", parseEx);
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            step.put("targetKey", targetKey);
            log.error("REPOSITORY_RESOLUTION_FAILED: caseId={}, targetKey={}", caseId, targetKey, e);
        }
        
        return step;
    }

    private Map<String, Object> collectJenkinsEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            var evidence = jenkinsEvidenceService.collect(caseId);
            
            // Validate Jenkins evidence success: require actual build data
            boolean hasBuildData = evidence.build() != null 
                    && evidence.build().buildNumber() != null
                    && evidence.build().commitSha() != null
                    && !evidence.build().commitSha().isBlank();
            
            boolean hasImageData = evidence.image() != null
                    && evidence.image().buildNumber() != null;
            
            if (!hasBuildData && !hasImageData) {
                step.put("result", "FAILED");
                step.put("error", "No valid Jenkins build or image data resolved");
                step.put("warnings", evidence.warnings());
                log.warn("JENKINS_NO_BUILD_DATA: caseId={}, warnings={}", caseId, evidence.warnings());
                return step;
            }
            
            if (hasBuildData && (evidence.build().commitSha() == null || evidence.build().commitSha().isBlank())) {
                step.put("result", "PARTIAL_SUCCESS");
                step.put("reason", "Build found but commit SHA missing");
                step.put("buildNumber", evidence.build().buildNumber());
                step.put("warnings", evidence.warnings());
                log.warn("JENKINS_MISSING_COMMIT: caseId={}, buildNumber={}", 
                        caseId, evidence.build().buildNumber());
                return step;
            }
            
            List<EvidenceEntity> jenkinsEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.JENKINS_BUILD || 
                                 ev.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT)
                    .toList();
            
            step.put("result", "SUCCESS");
            step.put("count", jenkinsEvidence.size());
            step.put("evidenceIds", jenkinsEvidence.stream().map(EvidenceEntity::getId).toList());
            step.put("jobName", evidence.build() != null ? evidence.build().jobName() : null);
            step.put("buildNumber", evidence.build() != null ? evidence.build().buildNumber() : null);
            step.put("commitSha", evidence.build() != null ? evidence.build().commitSha() : null);
            step.put("warnings", evidence.warnings());
            
            log.info("JENKINS_SUCCESS: caseId={}, buildNumber={}, commitSha={}", 
                    caseId, 
                    evidence.build() != null ? evidence.build().buildNumber() : null,
                    evidence.build() != null ? evidence.build().commitSha() : null);
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("JENKINS_COLLECTION_FAILED: caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> collectIncidentVersion(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            log.info("INCIDENT_VERSION_START: caseId={}", caseId);
            
            // Read Jenkins build context evidence
            List<EvidenceEntity> jenkinsEvidenceList = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT)
                    .toList();
            
            if (jenkinsEvidenceList.isEmpty()) {
                step.put("result", "FAILED");
                step.put("error", "JENKINS_BUILD_CONTEXT not found");
                log.error("INCIDENT_VERSION_NO_JENKINS: caseId={}", caseId);
                return step;
            }
            
            EvidenceEntity jenkinsEvidence = jenkinsEvidenceList.get(jenkinsEvidenceList.size() - 1);
            JenkinsCaseEvidence jenkinsCase = objectMapper.readValue(
                    jenkinsEvidence.getContentText(), 
                    JenkinsCaseEvidence.class
            );
            
            // Read repository resolution evidence for projectKey and branch
            List<EvidenceEntity> repoEvidenceList = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION)
                    .toList();
            
            if (repoEvidenceList.isEmpty()) {
                step.put("result", "FAILED");
                step.put("error", "REPOSITORY_RESOLUTION not found");
                log.error("INCIDENT_VERSION_NO_REPO: caseId={}", caseId);
                return step;
            }
            
            EvidenceEntity repoEvidence = repoEvidenceList.get(repoEvidenceList.size() - 1);
            RepositoryResolutionResult repoResolution = objectMapper.readValue(
                    repoEvidence.getContentText(), 
                    RepositoryResolutionResult.class
            );
            
            // Extract commit SHA from Jenkins build
            String jenkinsCommitSha = null;
            String jobName = null;
            Integer buildNumber = null;
            Long buildTimestamp = null;
            
            if (jenkinsCase.build() != null) {
                jenkinsCommitSha = jenkinsCase.build().commitSha();
                jobName = jenkinsCase.build().jobName();
                buildNumber = jenkinsCase.build().buildNumber();
                buildTimestamp = jenkinsCase.build().timestamp();
            }
            
            if (jenkinsCommitSha == null || jenkinsCommitSha.isBlank()) {
                step.put("result", "FAILED");
                step.put("error", "Jenkins commit SHA not found in build");
                step.put("jobName", jobName);
                step.put("buildNumber", buildNumber);
                log.error("INCIDENT_VERSION_NO_COMMIT: caseId={}, jobName={}, buildNumber={}", 
                        caseId, jobName, buildNumber);
                return step;
            }
            
            // Use Jenkins commit SHA for incident version
            String projectKey = repoResolution.projectKey();
            String repositorySlug = repoResolution.primaryRepositorySlug();
            String branch = "test2"; // From requirement: sourceBranch=test2
            
            List<String> warnings = new ArrayList<>();
            
            log.info("INCIDENT_VERSION_USING_JENKINS_COMMIT: caseId={}, commit={}, jobName={}, buildNumber={}", 
                    caseId, jenkinsCommitSha, jobName, buildNumber);
            
            // Create INCIDENT_VERSION evidence
            IncidentVersionResolution incidentVersion = new IncidentVersionResolution(
                    caseId,
                    repositorySlug,
                    branch,
                    "JENKINS_BUILD",
                    jenkinsCommitSha, // requestedSourceCommit
                    null, // requestedImageTag
                    Instant.now(), // incidentTime
                    jenkinsCommitSha, // resolvedCommitSha
                    null, // resolvedTag
                    buildTimestamp != null ? Instant.ofEpochMilli(buildTimestamp) : null, // commitTime
                    null, // commitMessage
                    true, // exactMatch - using Jenkins commit directly
                    warnings
            );
            
            // Save INCIDENT_VERSION evidence
            String incidentVersionJson = objectMapper.writeValueAsString(incidentVersion);
            evidenceService.save(
                    caseId,
                    EvidenceType.INCIDENT_VERSION,
                    "golden-path-incident-version",
                    incidentVersionJson,
                    false // No sanitization needed for incident version
            );
            
            step.put("result", "SUCCESS");
            step.put("jobName", jobName);
            step.put("buildNumber", buildNumber);
            step.put("jenkinsCommitSha", jenkinsCommitSha);
            step.put("checkoutCommitSha", jenkinsCommitSha);
            step.put("strategy", "JENKINS_BUILD");
            step.put("warnings", warnings);
            
            log.info("INCIDENT_VERSION_CREATED: caseId={}, jobName={}, buildNumber={}, commit={}", 
                    caseId, jobName, buildNumber, jenkinsCommitSha);
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("INCIDENT_VERSION_FAILED: caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> collectContextEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            log.info("CONTEXT_COLLECTION_START: caseId={}", caseId);
            
            // Call existing ReplayOrchestrator.collectContext() on the same case
            replayOrchestrator.collectContext(caseId);
            
            // Count collected evidence
            List<EvidenceEntity> allEvidence = evidenceRepository.findByCaseId(caseId);
            long lokiCount = allEvidence.stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.LOKI_LOG || 
                                 ev.getEvidenceType() == EvidenceType.LOKI_LOGS)
                    .count();
            long tempoCount = allEvidence.stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.TEMPO_TRACE || 
                                 ev.getEvidenceType() == EvidenceType.TEMPO_ENRICHMENT)
                    .count();
            long sourceContextCount = allEvidence.stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.SOURCE_CONTEXT)
                    .count();
            
            step.put("result", "SUCCESS");
            step.put("lokiEvidenceCount", lokiCount);
            step.put("tempoEvidenceCount", tempoCount);
            step.put("sourceContextCount", sourceContextCount);
            
            log.info("CONTEXT_COLLECTION_SUCCESS: caseId={}, loki={}, tempo={}, sourceContext={}", 
                    caseId, lokiCount, tempoCount, sourceContextCount);
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("CONTEXT_COLLECTION_FAILED: caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> reportLokiEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            List<EvidenceEntity> lokiEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.LOKI_LOG || 
                                 ev.getEvidenceType() == EvidenceType.LOKI_LOGS)
                    .toList();
            
            if (lokiEvidence.isEmpty()) {
                step.put("result", "UNAVAILABLE");
                step.put("reason", "No Loki evidence found");
                log.warn("LOKI_EVIDENCE_UNAVAILABLE: caseId={}", caseId);
            } else {
                step.put("result", "SUCCESS");
                step.put("count", lokiEvidence.size());
                step.put("evidenceIds", lokiEvidence.stream().map(EvidenceEntity::getId).toList());
                log.info("LOKI_EVIDENCE_FOUND: caseId={}, count={}", caseId, lokiEvidence.size());
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("LOKI_EVIDENCE_CHECK_FAILED: caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> reportTempoEvidence(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            List<EvidenceEntity> tempoEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.TEMPO_TRACE || 
                                 ev.getEvidenceType() == EvidenceType.TEMPO_ENRICHMENT)
                    .toList();
            
            if (tempoEvidence.isEmpty()) {
                step.put("result", "UNAVAILABLE");
                step.put("reason", "No Tempo trace available (optional)");
                log.info("TEMPO_EVIDENCE_UNAVAILABLE: caseId={} (optional)", caseId);
            } else {
                step.put("result", "SUCCESS");
                step.put("count", tempoEvidence.size());
                step.put("evidenceIds", tempoEvidence.stream().map(EvidenceEntity::getId).toList());
                log.info("TEMPO_EVIDENCE_FOUND: caseId={}, count={}", caseId, tempoEvidence.size());
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("TEMPO_EVIDENCE_CHECK_FAILED: caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> collectAiInputBundle(UUID caseId) {
        Map<String, Object> step = new LinkedHashMap<>();
        
        try {
            // Check if AI_INPUT_BUNDLE already exists (canonical or legacy source)
            List<EvidenceEntity> existingBundle = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.AI_INPUT_BUNDLE)
                    .filter(ev -> "replayfix-ai-bundle-builder".equals(ev.getSource()) ||
                                 "jenkins-validated-ai-bundle".equals(ev.getSource()))
                    .toList();
            
            if (!existingBundle.isEmpty()) {
                // Bundle already exists, check if INCIDENT_VERSION is present
                List<EvidenceEntity> incidentVersion = evidenceRepository.findByCaseId(caseId).stream()
                        .filter(ev -> ev.getEvidenceType() == EvidenceType.INCIDENT_VERSION)
                        .toList();
                
                if (incidentVersion.isEmpty()) {
                    step.put("result", "PARTIAL");
                    step.put("reason", "Bundle exists but INCIDENT_VERSION missing");
                    step.put("warnings", List.of(INCIDENT_VERSION_EVIDENCE_MISSING));
                    step.put("bundleCreated", true);
                    step.put("evidenceId", existingBundle.get(existingBundle.size() - 1).getId());
                    log.warn("AI_INPUT_BUNDLE_MISSING_INCIDENT_VERSION: caseId={}", caseId);
                } else {
                    step.put("result", "SUCCESS");
                    step.put("bundleCreated", true);
                    step.put("evidenceId", existingBundle.get(existingBundle.size() - 1).getId());
                    log.info("AI_INPUT_BUNDLE_EXISTS: caseId={}, evidenceId={}", 
                            caseId, existingBundle.get(existingBundle.size() - 1).getId());
                }
                return step;
            }
            
            log.info("AI_INPUT_BUNDLE_START: caseId={}", caseId);
            
            // Call AiInputBundleRefreshService to create AI bundle
            // This does NOT call external AI - it just creates the internal evidence bundle
            aiInputBundleRefreshService.refresh(caseId);
            
            // Check if AI_INPUT_BUNDLE was created
            List<EvidenceEntity> bundleEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.AI_INPUT_BUNDLE)
                    .filter(ev -> "replayfix-ai-bundle-builder".equals(ev.getSource()) ||
                                 "jenkins-validated-ai-bundle".equals(ev.getSource()))
                    .toList();
            
            if (bundleEvidence.isEmpty()) {
                step.put("result", "FAILED");
                step.put("error", "AI_INPUT_BUNDLE not created");
                step.put("bundleCreated", false);
                log.error("AI_INPUT_BUNDLE_NOT_CREATED: caseId={}", caseId);
            } else {
                // Check if INCIDENT_VERSION is present
                List<EvidenceEntity> incidentVersion = evidenceRepository.findByCaseId(caseId).stream()
                        .filter(ev -> ev.getEvidenceType() == EvidenceType.INCIDENT_VERSION)
                        .toList();
                
                if (incidentVersion.isEmpty()) {
                    step.put("result", "PARTIAL");
                    step.put("reason", "Bundle created but INCIDENT_VERSION missing");
                    step.put("warnings", List.of(INCIDENT_VERSION_EVIDENCE_MISSING));
                    step.put("bundleCreated", true);
                    step.put("evidenceId", bundleEvidence.get(bundleEvidence.size() - 1).getId());
                    log.warn("AI_INPUT_BUNDLE_CREATED_WITHOUT_INCIDENT_VERSION: caseId={}", caseId);
                } else {
                    step.put("result", "SUCCESS");
                    step.put("bundleCreated", true);
                    step.put("evidenceId", bundleEvidence.get(bundleEvidence.size() - 1).getId());
                    log.info("AI_INPUT_BUNDLE_CREATED: caseId={}, evidenceId={}", 
                            caseId, bundleEvidence.get(bundleEvidence.size() - 1).getId());
                }
            }
            
        } catch (Exception e) {
            // Check if bundle exists despite exception
            List<EvidenceEntity> bundleEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.AI_INPUT_BUNDLE)
                    .filter(ev -> "replayfix-ai-bundle-builder".equals(ev.getSource()) ||
                                 "jenkins-validated-ai-bundle".equals(ev.getSource()))
                    .toList();
            
            if (!bundleEvidence.isEmpty()) {
                step.put("result", "PARTIAL");
                step.put("reason", "Bundle exists but refresh failed: " + e.getMessage());
                step.put("bundleCreated", true);
                step.put("evidenceId", bundleEvidence.get(bundleEvidence.size() - 1).getId());
                log.warn("AI_INPUT_BUNDLE_REFRESH_FAILED: caseId={}", caseId, e);
            } else if (isIncidentVersionMissing(e)) {
                step.put("result", "PARTIAL");
                step.put("reason", "INCIDENT_VERSION evidence missing; AI input bundle refresh skipped");
                step.put("warnings", List.of(INCIDENT_VERSION_EVIDENCE_MISSING));
                step.put("bundleCreated", false);
                log.warn(
                        "AI_INPUT_BUNDLE_SKIPPED_MISSING_INCIDENT_VERSION: caseId={}",
                        caseId
                );
            } else {
                step.put("result", "FAILED");
                step.put("error", e.getMessage());
                step.put("bundleCreated", false);
                log.error("AI_INPUT_BUNDLE_FAILED: caseId={}", caseId, e);
            }
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
            // Check if INCIDENT_VERSION exists (required for source-aware deterministic RCA)
            List<EvidenceEntity> incidentVersion = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.INCIDENT_VERSION)
                    .toList();
            
            if (incidentVersion.isEmpty()) {
                step.put("result", "SKIPPED");
                step.put("reason", "INCIDENT_VERSION_NOT_AVAILABLE");
                log.warn("DETERMINISTIC_RCA_SKIPPED: INCIDENT_VERSION not found for caseId={}", caseId);
                return step;
            }
            
            log.info("DETERMINISTIC_RCA_START: caseId={}", caseId);
            
            deterministicRcaService.refresh(caseId);
            
            List<EvidenceEntity> rcaEvidence = evidenceRepository.findByCaseId(caseId).stream()
                    .filter(ev -> ev.getEvidenceType() == EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                    .toList();
            
            if (rcaEvidence.isEmpty()) {
                step.put("result", "FAILED");
                step.put("reason", "RCA generation failed - no evidence created");
                log.warn("DETERMINISTIC_RCA_NO_OUTPUT: caseId={}", caseId);
            } else {
                EvidenceEntity rca = rcaEvidence.get(rcaEvidence.size() - 1);
                step.put("result", "SUCCESS");
                step.put("evidenceId", rca.getId());
                step.put("confidence", rca.getConfidence());
                step.put("source", rca.getSource());
                step.put("synthetic", false);
                log.info("DETERMINISTIC_RCA_SUCCESS: caseId={}, evidenceId={}", caseId, rca.getId());
            }
            
        } catch (Exception e) {
            step.put("result", "FAILED");
            step.put("error", e.getMessage());
            log.error("DETERMINISTIC_RCA_FAILED: caseId={}", caseId, e);
        }
        
        return step;
    }

    private Map<String, Object> buildSummary(UUID caseId) {
        return buildSummary(caseId, null);
    }
    
    private Map<String, Object> buildSummary(UUID caseId, Map<String, Object> steps) {
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
            if (!evidenceByType.containsKey("REPOSITORY_RESOLUTION")) {
                missingEvidence.add("REPOSITORY_RESOLUTION");
            }
            if (!evidenceByType.containsKey("JENKINS_BUILD_CONTEXT")) {
                missingEvidence.add("JENKINS_BUILD_CONTEXT");
            }
            if (!evidenceByType.containsKey("INCIDENT_VERSION")) {
                missingEvidence.add("INCIDENT_VERSION");
            }
            if (!evidenceByType.containsKey("LOKI_LOG")) {
                missingEvidence.add("LOKI_LOG");
            }
            if (!evidenceByType.containsKey("TEMPO_ENRICHMENT")) {
                missingEvidence.add("TEMPO_ENRICHMENT (optional)");
            }
            if (!evidenceByType.containsKey("AI_INPUT_BUNDLE")) {
                missingEvidence.add("AI_INPUT_BUNDLE");
            }
            if (!evidenceByType.containsKey("DETERMINISTIC_ROOT_CAUSE")) {
                missingEvidence.add("DETERMINISTIC_ROOT_CAUSE");
            }
            
            // Also check step results for failed mandatory final steps
            if (steps != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> aiInputBundleStep = (Map<String, Object>) steps.get("9_ai_input_bundle");
                if (aiInputBundleStep != null && "FAILED".equals(aiInputBundleStep.get("result"))) {
                    if (!missingEvidence.contains("AI_INPUT_BUNDLE")) {
                        missingEvidence.add("AI_INPUT_BUNDLE (step failed)");
                    }
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> rcaStep = (Map<String, Object>) steps.get("10_deterministic_rca");
                if (rcaStep != null && "FAILED".equals(rcaStep.get("result"))) {
                    if (!missingEvidence.contains("DETERMINISTIC_ROOT_CAUSE")) {
                        missingEvidence.add("DETERMINISTIC_ROOT_CAUSE (step failed)");
                    }
                }
            }
            
            summary.put("missingEvidence", missingEvidence);
            
            // Add evidence quality assessment
            try {
                EvidenceQuality quality = assessEvidenceQuality(allEvidence);
                summary.put("evidenceQuality", quality);
            } catch (Exception ex) {
                log.warn("Failed to assess evidence quality: {}", ex.getMessage());
            }
            
        } catch (Exception e) {
            summary.put("error", e.getMessage());
        }
        
        return summary;
    }
    
    private EvidenceQuality assessEvidenceQuality(List<EvidenceEntity> allEvidence) {
        String jiraSignalQuality = "UNKNOWN";
        LokiQualityData lokiQuality = new LokiQualityData(0, 0, 0, false, false);
        boolean tempoTraceFound = false;
        SourceQualityData sourceQuality = new SourceQualityData(0, 0);
        RcaQualityData rcaQuality = new RcaQualityData(0.0, "UNCLASSIFIED");
        
        try {
            // Assess JIRA signal quality
            jiraSignalQuality = allEvidence.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE)
                    .findFirst()
                    .map(jira -> {
                        String content = jira.getContentText();
                        return (content != null && content.length() > 200) ? "SUFFICIENT" : "WEAK";
                    })
                    .orElse("UNKNOWN");
            
            // Assess Loki log quality
            lokiQuality = allEvidence.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.LOKI_LOG)
                    .findFirst()
                    .map(loki -> {
                        try {
                            AdaptiveLokiSearchResult lokiResult = 
                                objectMapper.readValue(loki.getContentText(), AdaptiveLokiSearchResult.class);
                            
                            int matchedRows = lokiResult.logs() != null ? lokiResult.logs().size() : 0;
                            int totalQueries = lokiResult.attempts() != null ? lokiResult.attempts().size() : 0;
                            int failedQueries = lokiResult.attempts() != null 
                                ? (int) lokiResult.attempts().stream().filter(attempt -> attempt.error() != null).count()
                                : 0;
                            
                            boolean corrIdFound = false;
                            boolean trIdFound = false;
                            
                            if (lokiResult.logs() != null) {
                                for (var log : lokiResult.logs()) {
                                    String line = log.line();
                                    if (line != null) {
                                        if (line.contains("correlationId") || line.contains("correlation-id")) {
                                            corrIdFound = true;
                                        }
                                        if (line.contains("traceId") || line.contains("trace-id")) {
                                            trIdFound = true;
                                        }
                                    }
                                }
                            }
                            
                            return new LokiQualityData(matchedRows, failedQueries, totalQueries, corrIdFound, trIdFound);
                        } catch (Exception ex) {
                            log.warn("Failed to parse Loki evidence: {}", ex.getMessage());
                            return new LokiQualityData(0, 0, 0, false, false);
                        }
                    })
                    .orElse(new LokiQualityData(0, 0, 0, false, false));
            
            // Assess Tempo trace quality
            tempoTraceFound = allEvidence.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.TEMPO_TRACE)
                    .findFirst()
                    .isPresent();
            
            // Assess source context quality
            sourceQuality = allEvidence.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.SOURCE_CONTEXT)
                    .findFirst()
                    .map(source -> {
                        try {
                            SourceContextResult sourceResult = 
                                objectMapper.readValue(source.getContentText(), SourceContextResult.class);
                            int scannedCount = sourceResult.scannedFileCount();
                            int matchedCount = sourceResult.excerpts() != null ? sourceResult.excerpts().size() : 0;
                            return new SourceQualityData(scannedCount, matchedCount);
                        } catch (Exception ex) {
                            log.warn("Failed to parse source context: {}", ex.getMessage());
                            return new SourceQualityData(0, 0);
                        }
                    })
                    .orElse(new SourceQualityData(0, 0));
            
            // Assess RCA quality
            rcaQuality = allEvidence.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                    .findFirst()
                    .map(rca -> {
                        try {
                            DeterministicRootCauseReport rcaReport = 
                                objectMapper.readValue(rca.getContentText(), DeterministicRootCauseReport.class);
                            double confidence = rca.getConfidence() != null ? rca.getConfidence() : 0.0;
                            String classification = rcaReport.classification() != null 
                                ? rcaReport.classification() 
                                : "UNCLASSIFIED";
                            return new RcaQualityData(confidence, classification);
                        } catch (Exception ex) {
                            log.warn("Failed to parse RCA evidence: {}", ex.getMessage());
                            return new RcaQualityData(0.0, "UNCLASSIFIED");
                        }
                    })
                    .orElse(new RcaQualityData(0.0, "UNCLASSIFIED"));
            
        } catch (Exception e) {
            log.error("Error assessing evidence quality: {}", e.getMessage());
            return new EvidenceQuality(
                "UNKNOWN", 0, 0, 0, false, false, false, 0, 0, 0.0, "UNCLASSIFIED", "WEAK", "Error assessing quality"
            );
        }
        
        // Calculate demo readiness
        String demoReadiness = calculateDemoReadiness(
                lokiQuality.matchedRows(),
                tempoTraceFound,
                sourceQuality.matchedFileCount(),
                rcaQuality.confidence(),
                rcaQuality.classification()
        );
        
        // Overall assessment
        String overallAssessment = buildOverallAssessment(
                lokiQuality.matchedRows(),
                lokiQuality.failedQueryCount(),
                sourceQuality.scannedFileCount(),
                sourceQuality.matchedFileCount(),
                rcaQuality.confidence()
        );
        
        return new EvidenceQuality(
                jiraSignalQuality,
                lokiQuality.matchedRows(),
                lokiQuality.failedQueryCount(),
                lokiQuality.totalQueryCount(),
                lokiQuality.correlationIdFound(),
                lokiQuality.traceIdFound(),
                tempoTraceFound,
                sourceQuality.scannedFileCount(),
                sourceQuality.matchedFileCount(),
                rcaQuality.confidence(),
                rcaQuality.classification(),
                demoReadiness,
                overallAssessment
        );
    }
    
    // Helper records for evidence quality assessment
    private record LokiQualityData(
            int matchedRows,
            int failedQueryCount,
            int totalQueryCount,
            boolean correlationIdFound,
            boolean traceIdFound
    ) {}
    
    private record SourceQualityData(
            int scannedFileCount,
            int matchedFileCount
    ) {}
    
    private record RcaQualityData(
            double confidence,
            String classification
    ) {}
    
    private String calculateDemoReadiness(
            int lokiMatchedRows,
            boolean tempoTraceFound,
            int sourceMatchedFileCount,
            double rcaConfidence,
            String rcaClassification
    ) {
        boolean hasStrongLogs = lokiMatchedRows > 50;
        boolean hasTrace = tempoTraceFound;
        boolean hasSource = sourceMatchedFileCount > 0;
        boolean hasStrongRCA = rcaConfidence > 0.7 && !"UNCLASSIFIED".equals(rcaClassification);
        
        // STRONG: logs, trace/source, and strong RCA
        if (hasStrongLogs && (hasTrace || hasSource) && hasStrongRCA) {
            return "STRONG";
        }
        
        // MEDIUM: has some logs and valid incident version, but weak source/trace or medium RCA
        if (lokiMatchedRows > 10 && (hasSource || rcaConfidence > 0.3)) {
            return "MEDIUM";
        }
        
        // WEAK: RCA is only hypothesis or missing critical evidence
        return "WEAK";
    }
    
    private String buildOverallAssessment(
            int lokiMatchedRows,
            int lokiFailedQueryCount,
            int sourceScannedFileCount,
            int sourceMatchedFileCount,
            double rcaConfidence
    ) {
        List<String> issues = new ArrayList<>();
        
        if (lokiMatchedRows == 0) {
            issues.add("No Loki logs matched");
        } else if (lokiMatchedRows < 10) {
            issues.add("Very few logs matched (" + lokiMatchedRows + ")");
        }
        
        if (lokiFailedQueryCount > 0) {
            issues.add(lokiFailedQueryCount + " Loki queries failed");
        }
        
        if (sourceScannedFileCount == 0) {
            issues.add("No source files scanned");
        } else if (sourceMatchedFileCount == 0) {
            issues.add("Source scanned but no matches found");
        }
        
        if (rcaConfidence < 0.3) {
            issues.add("RCA confidence very low (" + String.format("%.2f", rcaConfidence) + ")");
        } else if (rcaConfidence < 0.6) {
            issues.add("RCA confidence moderate (" + String.format("%.2f", rcaConfidence) + ")");
        }
        
        if (issues.isEmpty()) {
            return "Evidence quality is strong";
        } else {
            return String.join("; ", issues);
        }
    }

    private String calculateOverallStatus(Map<String, Object> steps) {
        // Mandatory steps that must succeed for real incident path
        String[] mandatorySteps = {
            "0_target_validation",
            "1_case_resolution",
            "2_jira_evidence",
            "3_repository_resolution",
            "4_jenkins_evidence",
            "6_context_collection",
            "7_loki_evidence"  // Loki is required for real incident path
        };
        
        // Optional steps (SKIPPED or UNAVAILABLE is acceptable)
        String[] optionalSteps = {
            "8_tempo_evidence",      // Tempo is optional
            "5_incident_version",    // Missing version should yield needs-evidence, not failure
            "9_ai_input_bundle",     // Can fail if dependencies missing
            "10_deterministic_rca"    // Can be skipped if incident version unavailable
        };
        
        boolean anyMandatoryFailed = false;
        boolean anyNonMandatoryFailed = false;
        boolean anyStepSkipped = false;
        
        for (Map.Entry<String, Object> entry : steps.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stepResult = (Map<String, Object>) entry.getValue();
                String result = (String) stepResult.get("result");
                
                boolean isMandatory = false;
                for (String mandatoryStep : mandatorySteps) {
                    if (entry.getKey().equals(mandatoryStep)) {
                        isMandatory = true;
                        break;
                    }
                }
                
                boolean isOptional = false;
                for (String optionalStep : optionalSteps) {
                    if (entry.getKey().equals(optionalStep)) {
                        isOptional = true;
                        break;
                    }
                }
                
                if ("FAILED".equals(result)) {
                    if (isMandatory) {
                        anyMandatoryFailed = true;
                    } else {
                        anyNonMandatoryFailed = true;
                    }
                } else if ("SKIPPED".equals(result)
                        || "UNAVAILABLE".equals(result)
                        || "PARTIAL".equals(result)
                        || "PARTIAL_SUCCESS".equals(result)) {
                    if (isMandatory) {
                        anyMandatoryFailed = true;
                    } else if (!isOptional || "PARTIAL".equals(result)
                            || "PARTIAL_SUCCESS".equals(result)) {
                        anyStepSkipped = true;
                    }
                    // Optional steps can be SKIPPED/UNAVAILABLE without affecting status
                }
            }
        }
        
        if (anyMandatoryFailed) {
            return "FAILED";
        } else if (anyNonMandatoryFailed || anyStepSkipped) {
            return "PARTIAL_SUCCESS";
        } else {
            return "SUCCESS";
        }
    }

    private void mergeStepWarnings(
            Map<String, Object> step,
            List<String> warnings
    ) {
        Object stepWarnings = step.get("warnings");
        if (stepWarnings instanceof Collection<?> collection) {
            for (Object warning : collection) {
                if (warning != null && !warnings.contains(warning.toString())) {
                    warnings.add(warning.toString());
                }
            }
        }
    }

    private boolean hasMissingIncidentVersion(Map<String, Object> steps) {
        Object incidentVersionStep = steps.get("5_incident_version");
        if (stepHasMissingIncidentVersion(incidentVersionStep)) {
            return true;
        }
        Object aiInputBundleStep = steps.get("9_ai_input_bundle");
        return stepHasMissingIncidentVersion(aiInputBundleStep);
    }

    private boolean stepHasMissingIncidentVersion(Object step) {
        if (!(step instanceof Map<?, ?> stepMap)) {
            return false;
        }
        Object warnings = stepMap.get("warnings");
        if (warnings instanceof Collection<?> collection
                && collection.contains(INCIDENT_VERSION_EVIDENCE_MISSING)) {
            return true;
        }
        String reason = Objects.toString(stepMap.get("reason"), "");
        String error = Objects.toString(stepMap.get("error"), "");
        return reason.contains("INCIDENT_VERSION")
                || error.contains("INCIDENT_VERSION");
    }

    private boolean isIncidentVersionMissing(Exception exception) {
        String message = Objects.toString(exception.getMessage(), "");
        Throwable cause = exception.getCause();
        while (cause != null) {
            message = message + " " + Objects.toString(cause.getMessage(), "");
            cause = cause.getCause();
        }
        return message.contains("Required evidence not found")
                && message.contains("INCIDENT_VERSION");
    }
}
