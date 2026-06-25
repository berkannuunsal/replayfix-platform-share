package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.JiraCommentPublicationEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.JiraCommentPublishResponse;
import com.etiya.replaylab.model.JiraEvidenceCommentPublishResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.JiraCommentPublicationRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovedJiraEvidenceCommentPublishService {

    private static final Logger log = LoggerFactory.getLogger(ApprovedJiraEvidenceCommentPublishService.class);

    private final ReplayLabProperties properties;
    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JiraCommentPublicationRepository publicationRepository;
    private final ApprovalService approvalService;
    private final JiraClient jiraClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ApprovedJiraEvidenceCommentPublishService(
            ReplayLabProperties properties,
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JiraCommentPublicationRepository publicationRepository,
            ApprovalService approvalService,
            JiraClient jiraClient,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.publicationRepository = publicationRepository;
        this.approvalService = approvalService;
        this.jiraClient = jiraClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JiraEvidenceCommentPublishResult publish(
            UUID caseId,
            UUID previewEvidenceId,
            UUID approvalId
    ) {
        List<String> warnings = new ArrayList<>();

        if (!properties.getPolicy().isAllowJiraCommentWrite()) {
            String error = "Jira comment write policy is disabled";
            log.warn(error);
            return new JiraEvidenceCommentPublishResult(
                    false,
                    caseId,
                    null,
                    previewEvidenceId,
                    approvalId,
                    null,
                    null,
                    null,
                    "POLICY_DENIED",
                    List.of(error),
                    error
            );
        }

        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (caseEntity.getJiraKey() == null || caseEntity.getJiraKey().isBlank()) {
            throw new IllegalStateException("Case has no Jira key");
        }

        approvalService.requireApprovedJiraEvidenceSummary(caseId, previewEvidenceId);

        EvidenceEntity previewEvidence = evidenceRepository.findById(previewEvidenceId)
                .orElseThrow(() -> new IllegalArgumentException("Preview evidence not found"));

        if (previewEvidence.getEvidenceType() != EvidenceType.REPLAY_OUTPUT) {
            throw new IllegalArgumentException("Evidence is not REPLAY_OUTPUT type");
        }

        if (!"jira-evidence-summary-preview".equals(previewEvidence.getSource())) {
            throw new IllegalArgumentException("Evidence source is not jira-evidence-summary-preview");
        }

        JsonNode previewBody;
        try {
            previewBody = objectMapper.readTree(previewEvidence.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse preview evidence body", e);
        }

        String contentSha256 = previewBody.path("contentSha256").asText();
        JsonNode adfBody = previewBody.path("adfBody");

        if (contentSha256 == null || contentSha256.isBlank()) {
            throw new IllegalStateException("Preview evidence has no content SHA-256");
        }

        if (adfBody == null || adfBody.isNull()) {
            throw new IllegalStateException("Preview evidence has no ADF body");
        }

        String recomputedHash = computeSha256(adfBody.toString());
        if (!contentSha256.equals(recomputedHash)) {
            warnings.add("Content SHA-256 mismatch, preview may have been modified");
        }

        var existing = publicationRepository.findByIssueKeyAndContentSha256(
                caseEntity.getJiraKey(),
                contentSha256
        );

        if (existing.isPresent()) {
            JiraCommentPublicationEntity existingEntity = existing.get();
            if ("PUBLISHED".equals(existingEntity.getStatus())) {
                log.info("Comment already published: {}", existingEntity.getId());

                auditService.record(
                        caseId,
                        "JIRA_EVIDENCE_SUMMARY_DUPLICATE_SKIPPED",
                        "system",
                        "Comment with same content already published"
                );

                return new JiraEvidenceCommentPublishResult(
                        true,
                        caseId,
                        caseEntity.getJiraKey(),
                        previewEvidenceId,
                        approvalId,
                        existingEntity.getId(),
                        existingEntity.getJiraCommentId(),
                        contentSha256,
                        "DUPLICATE_SKIPPED",
                        warnings,
                        null
                );
            }
        }

        JiraCommentPublicationEntity publication = new JiraCommentPublicationEntity();
        publication.setCaseId(caseId);
        publication.setIssueKey(caseEntity.getJiraKey());
        publication.setPreviewEvidenceId(previewEvidenceId);
        publication.setApprovalId(approvalId);
        publication.setContentSha256(contentSha256);
        publication.setStatus("PENDING");

        publication = publicationRepository.save(publication);

        try {
            JiraCommentPublishResponse response = jiraClient.addCommentAdf(
                    caseEntity.getJiraKey(),
                    adfBody
            );

            if (response.success()) {
                publication.setStatus("PUBLISHED");
                publication.setJiraCommentId(response.commentId());
                publication.setPublishedAt(Instant.now());

                publicationRepository.save(publication);

                EvidenceEntity publicationEvidence = new EvidenceEntity();
                publicationEvidence.setCaseId(caseId);
                publicationEvidence.setEvidenceType(EvidenceType.REPLAY_OUTPUT);
                publicationEvidence.setSource("jira-evidence-summary-publication");
                publicationEvidence.setConfidence(1.0);
                publicationEvidence.setBody(objectMapper.createObjectNode()
                        .put("publicationId", publication.getId().toString())
                        .put("jiraCommentId", response.commentId())
                        .put("issueKey", caseEntity.getJiraKey())
                        .put("publishedAt", publication.getPublishedAt().toString())
                        .toString());

                evidenceRepository.save(publicationEvidence);

                auditService.record(
                        caseId,
                        "JIRA_EVIDENCE_SUMMARY_PUBLISHED",
                        "system",
                        "Evidence summary published to Jira comment " + response.commentId()
                );

                log.info("Successfully published Jira comment {} for case {}", response.commentId(), caseId);

                return new JiraEvidenceCommentPublishResult(
                        true,
                        caseId,
                        caseEntity.getJiraKey(),
                        previewEvidenceId,
                        approvalId,
                        publication.getId(),
                        response.commentId(),
                        contentSha256,
                        "PUBLISHED",
                        warnings,
                        null
                );

            } else {
                publication.setStatus("FAILED");
                publication.setErrorCategory("JIRA_API_ERROR");
                publication.setErrorMessage(response.warnings().toString());

                publicationRepository.save(publication);

                auditService.record(
                        caseId,
                        "JIRA_EVIDENCE_SUMMARY_PUBLISH_FAILED",
                        "system",
                        "Failed to publish: " + response.warnings()
                );

                return new JiraEvidenceCommentPublishResult(
                        false,
                        caseId,
                        caseEntity.getJiraKey(),
                        previewEvidenceId,
                        approvalId,
                        publication.getId(),
                        null,
                        contentSha256,
                        "FAILED",
                        response.warnings(),
                        "Jira API returned error"
                );
            }

        } catch (Exception e) {
            log.error("Failed to publish Jira comment for case {}", caseId, e);

            publication.setStatus("FAILED");
            publication.setErrorCategory("UNEXPECTED_ERROR");
            publication.setErrorMessage(e.getMessage());

            publicationRepository.save(publication);

            auditService.record(
                    caseId,
                    "JIRA_EVIDENCE_SUMMARY_PUBLISH_FAILED",
                    "system",
                    "Exception during publish: " + e.getMessage()
            );

            return new JiraEvidenceCommentPublishResult(
                    false,
                    caseId,
                    caseEntity.getJiraKey(),
                    previewEvidenceId,
                    approvalId,
                    publication.getId(),
                    null,
                    contentSha256,
                    "FAILED",
                    List.of("Exception: " + e.getMessage()),
                    e.getMessage()
            );
        }
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
