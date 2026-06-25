package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.JiraEvidenceCommentPreview;
import com.etiya.replaylab.model.JiraEvidenceSnapshot;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class JiraEvidenceCommentPreviewService {

    private static final Logger log = LoggerFactory.getLogger(JiraEvidenceCommentPreviewService.class);
    private static final int MAX_COMMENT_CHARS = 25000;

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JiraEvidenceSnapshotBuilder snapshotBuilder;
    private final JiraEvidenceAdfRenderer adfRenderer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public JiraEvidenceCommentPreviewService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JiraEvidenceSnapshotBuilder snapshotBuilder,
            JiraEvidenceAdfRenderer adfRenderer,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.adfRenderer = adfRenderer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JiraEvidenceCommentPreview createPreview(UUID caseId) {
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (caseEntity.getJiraKey() == null || caseEntity.getJiraKey().isBlank()) {
            throw new IllegalStateException("Case has no Jira key");
        }

        log.info("Creating Jira evidence preview for case {}", caseId);

        JiraEvidenceSnapshot snapshot = snapshotBuilder.build(caseId);

        JsonNode adfBody = adfRenderer.render(snapshot);
        String plainTextPreview = adfRenderer.renderPlainText(snapshot);

        String adfBodyJson = adfBody.toString();
        String contentSha256 = computeSha256(adfBodyJson);

        List<EvidenceEntity> existing = evidenceRepository.findByCaseIdAndEvidenceTypeAndSource(
                caseId,
                EvidenceType.REPLAY_OUTPUT,
                "jira-evidence-summary-preview"
        );

        UUID previewEvidenceId = null;
        boolean isNew = true;

        for (EvidenceEntity existingEvidence : existing) {
            try {
                JsonNode existingBody = objectMapper.readTree(existingEvidence.getBody());
                String existingSha = existingBody.path("contentSha256").asText();
                if (contentSha256.equals(existingSha)) {
                    previewEvidenceId = existingEvidence.getId();
                    isNew = false;
                    log.info("Reusing existing preview evidence {}", previewEvidenceId);
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to check existing preview: {}", e.getMessage());
            }
        }

        if (isNew) {
            EvidenceEntity previewEvidence = new EvidenceEntity();
            previewEvidence.setCaseId(caseId);
            previewEvidence.setEvidenceType(EvidenceType.REPLAY_OUTPUT);
            previewEvidence.setSource("jira-evidence-summary-preview");
            previewEvidence.setConfidence(1.0);

            try {
                String previewBody = objectMapper.createObjectNode()
                        .put("analysisId", snapshot.analysisId())
                        .put("contentSha256", contentSha256)
                        .put("plainTextLength", plainTextPreview.length())
                        .put("sanitized", true)
                        .put("published", false)
                        .set("adfBody", adfBody)
                        .toString();

                previewEvidence.setBody(previewBody);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize preview body", e);
            }

            previewEvidence = evidenceRepository.save(previewEvidence);
            previewEvidenceId = previewEvidence.getId();

            auditService.record(
                    caseId,
                    "JIRA_EVIDENCE_SUMMARY_PREVIEW_CREATED",
                    "system",
                    "Evidence preview created: " + previewEvidenceId
            );
        }

        List<String> warnings = new ArrayList<>(snapshot.warnings());

        if (plainTextPreview.length() > MAX_COMMENT_CHARS) {
            warnings.add("Preview exceeds max comment length (" + plainTextPreview.length() + " > " + MAX_COMMENT_CHARS + ")");
        }

        return new JiraEvidenceCommentPreview(
                caseId,
                caseEntity.getJiraKey(),
                previewEvidenceId,
                contentSha256,
                plainTextPreview,
                adfBody,
                plainTextPreview.length(),
                true,
                false,
                false,
                warnings
        );
    }

    private String computeSha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SHA-256", e);
        }
    }
}
