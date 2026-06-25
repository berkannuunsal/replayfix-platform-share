package com.etiya.replaylab.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_jira_comment_publication")
public class JiraCommentPublicationEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "issue_key", nullable = false, length = 100)
    private String issueKey;

    @Column(name = "preview_evidence_id", nullable = false)
    private UUID previewEvidenceId;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "jira_comment_id", length = 100)
    private String jiraCommentId;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "error_category", length = 100)
    private String errorCategory;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (requestedAt == null) requestedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }
    
    public String getIssueKey() { return issueKey; }
    public void setIssueKey(String issueKey) { this.issueKey = issueKey; }
    
    public UUID getPreviewEvidenceId() { return previewEvidenceId; }
    public void setPreviewEvidenceId(UUID previewEvidenceId) { this.previewEvidenceId = previewEvidenceId; }
    
    public UUID getApprovalId() { return approvalId; }
    public void setApprovalId(UUID approvalId) { this.approvalId = approvalId; }
    
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    
    public String getJiraCommentId() { return jiraCommentId; }
    public void setJiraCommentId(String jiraCommentId) { this.jiraCommentId = jiraCommentId; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    
    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String errorCategory) { this.errorCategory = errorCategory; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
