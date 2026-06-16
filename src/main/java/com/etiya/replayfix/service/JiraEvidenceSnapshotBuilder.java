package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceAvailability;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.JiraEvidenceMatrixItem;
import com.etiya.replayfix.model.JiraEvidenceSnapshot;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class JiraEvidenceSnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(JiraEvidenceSnapshotBuilder.class);

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final EvidenceSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    public JiraEvidenceSnapshotBuilder(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            EvidenceSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public JiraEvidenceSnapshot build(UUID caseId) {
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        List<EvidenceEntity> evidenceList = evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId);

        List<String> warnings = new ArrayList<>();
        List<JiraEvidenceMatrixItem> evidenceMatrix = new ArrayList<>();

        String businessImpact = null;
        String technicalSymptom = null;
        String affectedFlow = null;
        List<String> probableFailureChain = new ArrayList<>();
        String probableRootCause = null;
        double rootCauseConfidence = 0.0;
        List<String> competingHypotheses = new ArrayList<>();
        List<String> regressionTestHypothesis = new ArrayList<>();
        List<String> minimumFixDirection = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        String recommendedNextAction = null;

        for (EvidenceEntity evidence : evidenceList) {
            processEvidence(evidence, evidenceMatrix, warnings);
        }

        if (evidenceMatrix.stream().noneMatch(item -> "JIRA".equals(item.source()))) {
            missingEvidence.add("Jira issue details not collected");
        }

        String analysisId = "RF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String workflowStatus = "EVIDENCE_COLLECTED";
        String generatedAt = Instant.now().toString();

        return new JiraEvidenceSnapshot(
                caseId,
                caseEntity.getJiraKey(),
                analysisId,
                workflowStatus,
                generatedAt,
                businessImpact != null ? businessImpact : "Not determined",
                technicalSymptom != null ? technicalSymptom : "See evidence matrix",
                affectedFlow != null ? affectedFlow : "See Jira issue summary",
                evidenceMatrix,
                probableFailureChain.isEmpty() ? List.of("Analysis pending") : probableFailureChain,
                probableRootCause != null ? probableRootCause : "Deterministic analysis pending",
                rootCauseConfidence,
                competingHypotheses,
                regressionTestHypothesis,
                minimumFixDirection,
                missingEvidence,
                recommendedNextAction != null ? recommendedNextAction : "Review evidence matrix and approve Jira comment publication",
                warnings
        );
    }

    private void processEvidence(
            EvidenceEntity evidence,
            List<JiraEvidenceMatrixItem> evidenceMatrix,
            List<String> warnings
    ) {
        try {
            String source = determineSource(evidence);
            EvidenceAvailability status = EvidenceAvailability.CONFIRMED;
            String keyFinding = extractKeyFinding(evidence);
            String confidence = determineConfidence(evidence);

            if (keyFinding == null || keyFinding.isBlank()) {
                return;
            }

            String sanitized = sanitizer.sanitize(keyFinding);
            if (sanitized == null || sanitized.isBlank()) {
                warnings.add("Evidence " + evidence.getId() + " sanitized to empty, excluded");
                return;
            }

            evidenceMatrix.add(new JiraEvidenceMatrixItem(
                    source,
                    status,
                    sanitized,
                    confidence,
                    evidence.getEvidenceType().name(),
                    evidence.getSource(),
                    evidence.getId().toString()
            ));

        } catch (Exception e) {
            log.warn("Failed to process evidence {}: {}", evidence.getId(), e.getMessage());
            warnings.add("Evidence processing error: " + evidence.getId());
        }
    }

    private String determineSource(EvidenceEntity evidence) {
        String source = evidence.getSource();
        if (source == null) return "UNKNOWN";

        if (source.contains("jira")) return "JIRA";
        if (source.contains("loki")) return "LOKI";
        if (source.contains("tempo")) return "TEMPO";
        if (source.contains("jenkins")) return "JENKINS";
        if (source.contains("bitbucket") || source.contains("repository")) return "BITBUCKET";
        if (source.contains("confluence")) return "CONFLUENCE";
        if (source.contains("kubernetes")) return "KUBERNETES";
        if (source.contains("root-cause")) return "ROOT_CAUSE_ANALYSIS";

        return source.toUpperCase();
    }

    private String extractKeyFinding(EvidenceEntity evidence) {
        try {
            String body = evidence.getBody();
            if (body == null) return null;

            JsonNode node = objectMapper.readTree(body);

            if (node.has("summary")) {
                return node.get("summary").asText();
            }

            if (node.has("keyFinding")) {
                return node.get("keyFinding").asText();
            }

            if (node.has("finding")) {
                return node.get("finding").asText();
            }

            if (body.length() > 500) {
                return body.substring(0, 500) + "...";
            }

            return body;

        } catch (Exception e) {
            String body = evidence.getBody();
            if (body != null && body.length() > 500) {
                return body.substring(0, 500) + "...";
            }
            return body;
        }
    }

    private String determineConfidence(EvidenceEntity evidence) {
        if (evidence.getConfidence() != null) {
            double conf = evidence.getConfidence();
            if (conf >= 0.9) return "High";
            if (conf >= 0.7) return "Medium";
            return "Low";
        }
        return "N/A";
    }
}
