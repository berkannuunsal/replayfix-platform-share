package com.etiya.replayfix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_code_change_advisory")
public class CodeChangeAdvisoryEntity {
    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "jira_key", nullable = false, length = 100)
    private String jiraKey;

    @Column(name = "target_key", nullable = false, length = 150)
    private String targetKey;

    @Column(name = "advisory_mode", nullable = false, length = 50)
    private String advisoryMode;

    @Column(name = "llm_used", nullable = false)
    private boolean llmUsed;

    @Column(name = "llm_status", nullable = false, length = 100)
    private String llmStatus;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(length = 100)
    private String confidence;

    @Column(name = "recommended_file", length = 1000)
    private String recommendedFile;

    @Column(name = "recommended_method_name", length = 300)
    private String recommendedMethodName;

    @Column(name = "recommended_change_type", length = 200)
    private String recommendedChangeType;

    @Column(name = "recommended_description", columnDefinition = "text")
    private String recommendedDescription;

    @Column(name = "recommended_pseudo_patch", columnDefinition = "text")
    private String recommendedPseudoPatch;

    @Column(name = "risks_json", columnDefinition = "text")
    private String risksJson;

    @Column(name = "missing_evidence_json", columnDefinition = "text")
    private String missingEvidenceJson;

    @Column(name = "test_suggestions_json", columnDefinition = "text")
    private String testSuggestionsJson;

    @Column(name = "should_proceed_to_patch", nullable = false)
    private boolean shouldProceedToPatch;

    @Column(name = "deterministic_fallback_reason", length = 200)
    private String deterministicFallbackReason;

    @Column(name = "safe_prompt_summary_json", columnDefinition = "text")
    private String safePromptSummaryJson;

    @Column(name = "response_snapshot_json", columnDefinition = "text")
    private String responseSnapshotJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID value) { this.id = value; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public String getJiraKey() { return jiraKey; }
    public void setJiraKey(String value) { this.jiraKey = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { this.targetKey = value; }
    public String getAdvisoryMode() { return advisoryMode; }
    public void setAdvisoryMode(String value) { this.advisoryMode = value; }
    public boolean isLlmUsed() { return llmUsed; }
    public void setLlmUsed(boolean value) { this.llmUsed = value; }
    public String getLlmStatus() { return llmStatus; }
    public void setLlmStatus(String value) { this.llmStatus = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String value) { this.confidence = value; }
    public String getRecommendedFile() { return recommendedFile; }
    public void setRecommendedFile(String value) { this.recommendedFile = value; }
    public String getRecommendedMethodName() { return recommendedMethodName; }
    public void setRecommendedMethodName(String value) { this.recommendedMethodName = value; }
    public String getRecommendedChangeType() { return recommendedChangeType; }
    public void setRecommendedChangeType(String value) { this.recommendedChangeType = value; }
    public String getRecommendedDescription() { return recommendedDescription; }
    public void setRecommendedDescription(String value) { this.recommendedDescription = value; }
    public String getRecommendedPseudoPatch() { return recommendedPseudoPatch; }
    public void setRecommendedPseudoPatch(String value) { this.recommendedPseudoPatch = value; }
    public String getRisksJson() { return risksJson; }
    public void setRisksJson(String value) { this.risksJson = value; }
    public String getMissingEvidenceJson() { return missingEvidenceJson; }
    public void setMissingEvidenceJson(String value) { this.missingEvidenceJson = value; }
    public String getTestSuggestionsJson() { return testSuggestionsJson; }
    public void setTestSuggestionsJson(String value) { this.testSuggestionsJson = value; }
    public boolean isShouldProceedToPatch() { return shouldProceedToPatch; }
    public void setShouldProceedToPatch(boolean value) { this.shouldProceedToPatch = value; }
    public String getDeterministicFallbackReason() { return deterministicFallbackReason; }
    public void setDeterministicFallbackReason(String value) { this.deterministicFallbackReason = value; }
    public String getSafePromptSummaryJson() { return safePromptSummaryJson; }
    public void setSafePromptSummaryJson(String value) { this.safePromptSummaryJson = value; }
    public String getResponseSnapshotJson() { return responseSnapshotJson; }
    public void setResponseSnapshotJson(String value) { this.responseSnapshotJson = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
