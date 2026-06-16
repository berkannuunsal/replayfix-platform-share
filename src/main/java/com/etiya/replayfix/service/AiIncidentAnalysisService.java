package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.*;
import com.etiya.replayfix.model.*;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.event.AiAnalysisCompletedEvent;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiIncidentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiIncidentAnalysisService.class);

    private final ReplayFixProperties properties;
    private final AiProviderClientFactory providerFactory;
    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AiIncidentAnalysisService(
            ReplayFixProperties properties,
            AiProviderClientFactory providerFactory,
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.providerFactory = providerFactory;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StructuredAiRootCauseAnalysis analyze(UUID caseId) {
        log.info("AI_ANALYSIS_STAGE=INIT: caseId={}", caseId);
        
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        auditService.record(caseId, "AI_ANALYSIS_REQUESTED", "system", 
                "AI root cause analysis requested");

        if (!properties.getAi().isEnabled()) {
            log.error("AI_ANALYSIS_STAGE=VALIDATION_FAILED: AI disabled, caseId={}", caseId);
            throw new IllegalStateException("AI integration is disabled");
        }

        log.info("AI_ANALYSIS_STAGE=EVIDENCE_VALIDATION: caseId={}", caseId);
        List<EvidenceEntity> evidenceList = evidenceRepository.findByCaseId(caseId);
        
        try {
            checkMinimumEvidence(evidenceList);
            log.info("AI_ANALYSIS_STAGE=EVIDENCE_VALIDATED: caseId={}, evidenceCount={}", 
                    caseId, evidenceList.size());
        } catch (IllegalStateException e) {
            log.error("AI_ANALYSIS_STAGE=EVIDENCE_VALIDATION_FAILED: caseId={}, reason={}", 
                    caseId, e.getMessage());
            throw e;
        }

        log.info("AI_ANALYSIS_STAGE=AI_GENERATION: caseId={}, provider={}", 
                caseId, properties.getAi().getProvider());
        AiProviderClient provider = providerFactory.getProvider();
        
        String model = properties.getAi().getModel();
        if (model == null || model.isBlank()) {
            model = "mock-replayfix-v1";
        }

        AiGenerationRequest request = new AiGenerationRequest(
                caseId,
                "FULL_INCIDENT_ANALYSIS",
                buildSystemPrompt(),
                buildUserPrompt(caseEntity, evidenceList),
                model,
                properties.getAi().getTemperature(),
                properties.getAi().getMaxOutputChars(),
                true,
                Map.of(
                        "caseId", caseId.toString(),
                        "jiraKey", caseEntity.getJiraKey() != null ? caseEntity.getJiraKey() : ""
                )
        );

        AiGenerationResponse response = provider.generate(request);
        
        log.info("AI_ANALYSIS_STAGE=AI_GENERATION_COMPLETED: caseId={}, success={}, provider={}", 
                caseId, response.success(), response.provider());

        if (!response.success()) {
            log.error("AI_ANALYSIS_STAGE=AI_GENERATION_FAILED: caseId={}, errorCategory={}, error={}", 
                    caseId, response.errorCategory(), response.errorMessage());
            
            auditService.record(caseId, "AI_ANALYSIS_FAILED", "system",
                    "AI analysis failed: " + response.errorMessage());
            
            // Publish failure event (notification will be created AFTER_COMMIT)
            eventPublisher.publishEvent(AiAnalysisCompletedEvent.failure(caseEntity, response.errorMessage()));
            
            throw new RuntimeException("AI analysis failed: " + response.errorMessage());
        }

        log.info("AI_ANALYSIS_STAGE=STRUCTURED_MAPPING: caseId={}", caseId);
        StructuredAiRootCauseAnalysis analysis;
        try {
            analysis = parseStructuredResponse(response);
            log.info("AI_ANALYSIS_STAGE=STRUCTURED_MAPPED: caseId={}, confidence={}, synthetic={}", 
                    caseId, analysis.confidence(), analysis.synthetic());
        } catch (Exception e) {
            log.error("AI_ANALYSIS_STAGE=STRUCTURED_MAPPING_FAILED: caseId={}", caseId, e);
            throw new RuntimeException("Failed to parse AI structured response", e);
        }
        
        log.info("AI_ANALYSIS_STAGE=CONFIDENCE_CAP: caseId={}, originalConfidence={}", 
                caseId, analysis.confidence());
        double cappedConfidence = applyConfidenceCap(analysis.confidence(), evidenceList);
        
        if (cappedConfidence != analysis.confidence()) {
            List<String> updatedWarnings = new ArrayList<>(analysis.warnings());
            updatedWarnings.add(String.format(
                    "Confidence capped from %.2f to %.2f based on evidence availability",
                    analysis.confidence(), cappedConfidence
            ));
            
            analysis = new StructuredAiRootCauseAnalysis(
                    analysis.caseId(),
                    analysis.analysisType(),
                    analysis.executiveSummary(),
                    analysis.probableRootCause(),
                    analysis.impactedComponent(),
                    cappedConfidence,
                    analysis.failureChain(),
                    analysis.supportingEvidenceIds(),
                    analysis.competingHypotheses(),
                    analysis.regressionTestHypothesis(),
                    analysis.minimumFixDirection(),
                    analysis.missingEvidence(),
                    analysis.recommendedNextAction(),
                    analysis.provider(),
                    analysis.model(),
                    analysis.synthetic(),
                    updatedWarnings
            );
        }

        log.info("AI_ANALYSIS_STAGE=AI_EVIDENCE_PERSISTENCE: caseId={}", caseId);
        try {
            persistAnalysis(caseId, analysis, provider.providerName());
            log.info("AI_ANALYSIS_STAGE=AI_EVIDENCE_PERSISTED: caseId={}, confidence={}", 
                    caseId, analysis.confidence());
        } catch (Exception e) {
            log.error("AI_ANALYSIS_STAGE=AI_EVIDENCE_PERSISTENCE_FAILED: caseId={}", caseId, e);
            throw new RuntimeException("Failed to persist AI analysis evidence", e);
        }

        log.info("AI_ANALYSIS_STAGE=AI_AUDIT_PERSISTENCE: caseId={}", caseId);
        try {
            auditService.record(caseId, "AI_ANALYSIS_COMPLETED", "system",
                    String.format("AI analysis completed: provider=%s, confidence=%.2f, synthetic=%b",
                            provider.providerName(), cappedConfidence, analysis.synthetic()));
            log.info("AI_ANALYSIS_STAGE=AI_AUDIT_PERSISTED: caseId={}", caseId);
        } catch (Exception e) {
            log.error("AI_ANALYSIS_STAGE=AI_AUDIT_PERSISTENCE_FAILED: caseId={}", caseId, e);
            throw new RuntimeException("Failed to persist AI analysis audit", e);
        }

        // Publish success event (notification will be created AFTER_COMMIT in separate transaction)
        eventPublisher.publishEvent(AiAnalysisCompletedEvent.success(caseEntity, cappedConfidence, analysis.synthetic()));

        log.info("AI_ANALYSIS_STAGE=COMPLETED: caseId={}, confidence={}, synthetic={}", 
                caseId, cappedConfidence, analysis.synthetic());
        return analysis;
    }

    private void checkMinimumEvidence(List<EvidenceEntity> evidenceList) {
        boolean hasJira = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE);
        
        if (!hasJira) {
            throw new IllegalStateException("INSUFFICIENT_EVIDENCE: Jira issue evidence required");
        }

        boolean hasRuntime = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.LOKI_LOGS ||
                              e.getEvidenceType() == EvidenceType.TEMPO_TRACE ||
                              e.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION ||
                              e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT ||
                              e.getEvidenceType() == EvidenceType.SOURCE_CONTEXT);

        if (!hasRuntime) {
            throw new IllegalStateException(
                    "INSUFFICIENT_EVIDENCE: At least one runtime evidence (Loki/Tempo/Repository/Jenkins/Source) required"
            );
        }
    }

    private double applyConfidenceCap(double originalConfidence, List<EvidenceEntity> evidenceList) {
        boolean hasJira = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE);
        boolean hasLoki = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.LOKI_LOGS);
        boolean hasTempo = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.TEMPO_TRACE);
        boolean hasSource = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION ||
                              e.getEvidenceType() == EvidenceType.SOURCE_CONTEXT);
        boolean hasJenkins = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT);

        double cap;
        if ((hasSource || hasJenkins) && hasLoki && hasTempo && hasJira) {
            cap = 0.85;
        } else if (hasLoki && hasTempo && hasJira) {
            cap = 0.75;
        } else if (hasLoki && hasJira) {
            cap = 0.65;
        } else if (hasJira) {
            cap = 0.35;
        } else {
            cap = 0.35;
        }

        if (properties.getAi().getProvider() == AiProviderType.MOCK) {
            cap = Math.min(cap, 0.75);
        }

        return Math.min(originalConfidence, cap);
    }

    private void persistAnalysis(UUID caseId, StructuredAiRootCauseAnalysis analysis, String providerName) {
        try {
            String content = objectMapper.writeValueAsString(analysis);
            
            EvidenceEntity evidence = new EvidenceEntity();
            evidence.setCaseId(caseId);
            evidence.setEvidenceType(EvidenceType.AI_ROOT_CAUSE);
            evidence.setSource("ai-provider-" + providerName.toLowerCase());
            evidence.setContentText(content);
            evidence.setBody(content);
            evidence.setSanitized(true);
            evidence.setConfidence(analysis.confidence());
            
            evidenceRepository.save(evidence);
            
            log.debug("AI analysis evidence persisted: caseId={}, provider={}, evidenceId={}, confidence={}",
                    caseId, providerName, evidence.getId(), analysis.confidence());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize AI analysis to JSON", e);
        }
    }

    private StructuredAiRootCauseAnalysis parseStructuredResponse(AiGenerationResponse response) {
        try {
            return objectMapper.treeToValue(
                    response.structuredResponse(),
                    StructuredAiRootCauseAnalysis.class
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to parse AI structured response", e);
        }
    }

    private String buildSystemPrompt() {
        return """
                You are an expert incident analysis system. Analyze the provided evidence and produce
                a structured root cause analysis. Base all facts on evidence. Mark inferences clearly.
                Do not invent evidence IDs, class names, methods, or commit SHAs.
                """;
    }

    private String buildUserPrompt(ReplayCaseEntity caseEntity, List<EvidenceEntity> evidenceList) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Incident: ").append(caseEntity.getJiraKey()).append("\n\n");
        prompt.append("Available Evidence:\n");
        
        for (EvidenceEntity evidence : evidenceList) {
            prompt.append("- ").append(evidence.getEvidenceType())
                    .append(" (ID: ").append(evidence.getId()).append(")\n");
            if (evidence.getContentText() != null && evidence.getContentText().length() < 500) {
                prompt.append("  ").append(evidence.getContentText().substring(0, 
                        Math.min(500, evidence.getContentText().length()))).append("\n");
            }
        }
        
        return prompt.toString();
    }
}
