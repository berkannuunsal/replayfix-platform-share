package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.RovoAnalysisSubmission;
import com.etiya.replaylab.model.RovoIncidentContext;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rovo Incident Commander Integration Service.
 * Provides sanitized context and receives RCA analysis from Rovo.
 */
@Service
public class RovoIncidentCommanderService {

    private static final Logger log = LoggerFactory.getLogger(RovoIncidentCommanderService.class);

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public RovoIncidentCommanderService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    public RovoIncidentContext getIncidentContext(String jiraKey) {
        log.info("ROVO_CONTEXT_REQUEST: jiraKey={}", jiraKey);
        
        ReplayCaseEntity caseEntity = caseRepository.findFirstByJiraKey(jiraKey)
                .orElseThrow(() -> new IllegalArgumentException("Case not found for jiraKey: " + jiraKey));
        
        List<EvidenceEntity> evidence = evidenceRepository.findByCaseId(caseEntity.getId());
        
        // Build sanitized context from evidence
        Map<String, List<UUID>> evidenceIdsByType = evidence.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEvidenceType().name(),
                        Collectors.mapping(EvidenceEntity::getId, Collectors.toList())
                ));
        
        // Extract specific evidence types
        RovoIncidentContext.LokiFindings lokiFindings = extractLokiFindings(evidence);
        RovoIncidentContext.TempoServiceChain tempoChain = extractTempoChain(evidence);
        RovoIncidentContext.JenkinsContext jenkinsContext = extractJenkinsContext(evidence, caseEntity);
        RovoIncidentContext.BitbucketContext bitbucketContext = extractBitbucketContext(evidence);
        RovoIncidentContext.SourceContext sourceContext = extractSourceContext(evidence);
        RovoIncidentContext.DeterministicRca deterministicRca = extractDeterministicRca(evidence);
        
        List<String> missingEvidence = identifyMissingEvidence(evidenceIdsByType);
        
        log.info("ROVO_CONTEXT_BUILT: jiraKey={}, caseId={}, evidenceCount={}", 
                jiraKey, caseEntity.getId(), evidence.size());
        
        return new RovoIncidentContext(
                caseEntity.getId(),
                caseEntity.getJiraKey(),
                extractJiraSummary(evidence),
                extractJiraDescriptionSummary(evidence),
                caseEntity.getApplicationName(),
                caseEntity.getTargetKey(),
                lokiFindings,
                tempoChain,
                jenkinsContext,
                bitbucketContext,
                sourceContext,
                deterministicRca,
                missingEvidence,
                evidenceIdsByType,
                caseEntity.getIncidentTime(),
                false // Real incident, not synthetic
        );
    }

    @Transactional
    public void submitAnalysis(String jiraKey, RovoAnalysisSubmission analysis) {
        log.info("ROVO_ANALYSIS_SUBMISSION: jiraKey={}", jiraKey);
        
        ReplayCaseEntity caseEntity = caseRepository.findFirstByJiraKey(jiraKey)
                .orElseThrow(() -> new IllegalArgumentException("Case not found for jiraKey: " + jiraKey));
        
        try {
            String content = objectMapper.writeValueAsString(analysis);
            
            EvidenceEntity evidence = new EvidenceEntity();
            evidence.setCaseId(caseEntity.getId());
            evidence.setEvidenceType(EvidenceType.ROVO_RCA);
            evidence.setSource("rovo-incident-commander");
            evidence.setContentText(content);
            evidence.setBody(content);
            evidence.setConfidence(analysis.confidence());
            evidence.setSanitized(true);
            
            evidenceRepository.save(evidence);
            
            log.info("ROVO_ANALYSIS_PERSISTED: jiraKey={}, caseId={}, confidence={}", 
                    jiraKey, caseEntity.getId(), analysis.confidence());
                    
        } catch (Exception e) {
            log.error("ROVO_ANALYSIS_PERSISTENCE_FAILED: jiraKey={}", jiraKey, e);
            throw new RuntimeException("Failed to persist Rovo analysis", e);
        }
    }

    private RovoIncidentContext.LokiFindings extractLokiFindings(List<EvidenceEntity> evidence) {
        List<EvidenceEntity> lokiEvidence = evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.LOKI_LOGS || 
                             e.getEvidenceType() == EvidenceType.LOKI_LOG)
                .toList();
        
        if (lokiEvidence.isEmpty()) {
            return null;
        }
        
        // Simplified extraction - can be enhanced
        return new RovoIncidentContext.LokiFindings(
                lokiEvidence.size(),
                List.of("ERROR", "WARN"), // Simplified
                List.of("WARNING"), // Simplified
                "Loki query executed",
                "Last 24 hours"
        );
    }

    private RovoIncidentContext.TempoServiceChain extractTempoChain(List<EvidenceEntity> evidence) {
        Optional<EvidenceEntity> tempoEvidence = evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.TEMPO_TRACE)
                .findFirst();
        
        if (tempoEvidence.isEmpty()) {
            return null;
        }
        
        // Simplified extraction - can be enhanced
        return new RovoIncidentContext.TempoServiceChain(
                "trace-id-placeholder",
                List.of("service1", "service2"),
                "service1",
                "operation1",
                10
        );
    }

    private RovoIncidentContext.JenkinsContext extractJenkinsContext(
            List<EvidenceEntity> evidence,
            ReplayCaseEntity caseEntity
    ) {
        return new RovoIncidentContext.JenkinsContext(
                caseEntity.getJenkinsJobName(),
                caseEntity.getJenkinsBuildNumber() != null ? caseEntity.getJenkinsBuildNumber() : 0,
                caseEntity.getSourceCommit(),
                "test2", // Target branch
                Instant.now(), // Simplified
                "SUCCESS" // Simplified
        );
    }

    private RovoIncidentContext.BitbucketContext extractBitbucketContext(List<EvidenceEntity> evidence) {
        Optional<EvidenceEntity> repoEvidence = evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION)
                .findFirst();
        
        // Simplified - can be enhanced with actual parsing
        return new RovoIncidentContext.BitbucketContext(
                "PROJECT",
                "repository-slug",
                "test2",
                "test2",
                "commit-sha-placeholder"
        );
    }

    private RovoIncidentContext.SourceContext extractSourceContext(List<EvidenceEntity> evidence) {
        List<EvidenceEntity> sourceEvidence = evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.SOURCE_CONTEXT)
                .toList();
        
        if (sourceEvidence.isEmpty()) {
            return new RovoIncidentContext.SourceContext(List.of(), "");
        }
        
        // Simplified - can be enhanced
        List<RovoIncidentContext.FileContext> files = List.of(
                new RovoIncidentContext.FileContext(
                        "src/main/java/Example.java",
                        "Example",
                        List.of("method1", "method2"),
                        100
                )
        );
        
        return new RovoIncidentContext.SourceContext(files, "commit-sha");
    }

    private RovoIncidentContext.DeterministicRca extractDeterministicRca(List<EvidenceEntity> evidence) {
        Optional<EvidenceEntity> rcaEvidence = evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.ROOT_CAUSE_ANALYSIS ||
                             e.getEvidenceType() == EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .findFirst();
        
        if (rcaEvidence.isEmpty()) {
            return null;
        }
        
        EvidenceEntity rca = rcaEvidence.get();
        return new RovoIncidentContext.DeterministicRca(
                "Deterministic RCA",
                "Root cause identified",
                rca.getConfidence() != null ? rca.getConfidence() : 0.0,
                rca.getSource(),
                List.of(rca.getId().toString())
        );
    }

    private String extractJiraSummary(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE)
                .findFirst()
                .map(e -> "Jira issue summary") // Simplified
                .orElse("No Jira summary");
    }

    private String extractJiraDescriptionSummary(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE)
                .findFirst()
                .map(e -> "Jira description summary") // Simplified
                .orElse("No Jira description");
    }

    private List<String> identifyMissingEvidence(Map<String, List<UUID>> evidenceByType) {
        List<String> missing = new ArrayList<>();
        
        if (!evidenceByType.containsKey("JIRA_ISSUE")) {
            missing.add("JIRA_ISSUE");
        }
        if (!evidenceByType.containsKey("LOKI_LOGS") && !evidenceByType.containsKey("LOKI_LOG")) {
            missing.add("LOKI_LOGS");
        }
        if (!evidenceByType.containsKey("JENKINS_BUILD") && !evidenceByType.containsKey("JENKINS_BUILD_CONTEXT")) {
            missing.add("JENKINS_BUILD");
        }
        if (!evidenceByType.containsKey("TEMPO_TRACE")) {
            missing.add("TEMPO_TRACE (optional)");
        }
        if (!evidenceByType.containsKey("ROOT_CAUSE_ANALYSIS") && !evidenceByType.containsKey("DETERMINISTIC_ROOT_CAUSE")) {
            missing.add("ROOT_CAUSE_ANALYSIS");
        }
        
        return missing;
    }
}
