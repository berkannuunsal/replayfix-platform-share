package com.etiya.replayfix.api;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AiAnalysisView;
import com.etiya.replayfix.model.StructuredAiRootCauseAnalysis;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class AiAnalysisViewController {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisViewController.class);

    private final ReplayFixProperties properties;
    private final EvidenceRepository evidenceRepository;
    private final AiProviderClientFactory providerFactory;
    private final ObjectMapper objectMapper;

    public AiAnalysisViewController(
            ReplayFixProperties properties,
            EvidenceRepository evidenceRepository,
            AiProviderClientFactory providerFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceRepository = evidenceRepository;
        this.providerFactory = providerFactory;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{caseId}/ai-analysis")
    public ResponseEntity<AiAnalysisView> getAiAnalysis(@PathVariable UUID caseId) {
        try {
            if (!properties.getAi().isEnabled()) {
                return ResponseEntity.ok(AiAnalysisView.disabled());
            }

            List<EvidenceEntity> evidenceList = evidenceRepository.findByCaseId(caseId);
            
            EvidenceEntity aiEvidence = evidenceList.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.AI_ROOT_CAUSE)
                    .filter(e -> e.getSource() != null && e.getSource().startsWith("ai-provider-"))
                    .reduce((first, second) -> second) // Get latest
                    .orElse(null);

            boolean hasMinimumEvidence = checkMinimumEvidence(evidenceList);
            boolean canGenerate = hasMinimumEvidence && aiEvidence == null;

            if (aiEvidence == null) {
                return ResponseEntity.ok(AiAnalysisView.unavailable(
                        hasMinimumEvidence 
                                ? "No AI analysis generated yet. Click 'Generate AI Analysis' to create one."
                                : "Insufficient evidence. Jira issue and at least one runtime evidence (Loki/Tempo) required."
                ));
            }

            StructuredAiRootCauseAnalysis analysis = objectMapper.readValue(
                    aiEvidence.getContentText(),
                    StructuredAiRootCauseAnalysis.class
            );

            return ResponseEntity.ok(AiAnalysisView.available(
                    analysis.provider(),
                    analysis.model(),
                    analysis,
                    canGenerate
            ));

        } catch (Exception e) {
            log.error("Failed to retrieve AI analysis for case {}", caseId, e);
            return ResponseEntity.ok(AiAnalysisView.unavailable(
                    "Error retrieving AI analysis"
            ));
        }
    }

    private boolean checkMinimumEvidence(List<EvidenceEntity> evidenceList) {
        boolean hasJira = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE);
        
        boolean hasRuntime = evidenceList.stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.LOKI_LOGS ||
                              e.getEvidenceType() == EvidenceType.TEMPO_TRACE ||
                              e.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION ||
                              e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT ||
                              e.getEvidenceType() == EvidenceType.SOURCE_CONTEXT);

        return hasJira && hasRuntime;
    }
}
