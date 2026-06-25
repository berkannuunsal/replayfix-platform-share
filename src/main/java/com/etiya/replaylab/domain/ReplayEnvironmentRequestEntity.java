package com.etiya.replaylab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_replay_environment_request")
public class ReplayEnvironmentRequestEntity {
    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "jira_key", nullable = false, length = 100)
    private String jiraKey;

    @Column(name = "target_key", nullable = false, length = 150)
    private String targetKey;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "requested_by", length = 200)
    private String requestedBy;

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by", length = 200)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 4000)
    private String rejectionReason;

    @Column(name = "approval_note", length = 4000)
    private String approvalNote;

    @Column(name = "plan_snapshot_json", columnDefinition = "text")
    private String planSnapshotJson;

    @Column(name = "replay_namespace", length = 200)
    private String replayNamespace;

    @Column(name = "proposed_host", length = 500)
    private String proposedHost;

    @Column(name = "dry_run_only", nullable = false)
    private boolean dryRunOnly;

    @Column(name = "real_provisioning_enabled", nullable = false)
    private boolean realProvisioningEnabled;

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
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String value) { this.requestedBy = value; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String value) { this.approvedBy = value; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant value) { this.approvedAt = value; }
    public String getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(String value) { this.rejectedBy = value; }
    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant value) { this.rejectedAt = value; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String value) { this.rejectionReason = value; }
    public String getApprovalNote() { return approvalNote; }
    public void setApprovalNote(String value) { this.approvalNote = value; }
    public String getPlanSnapshotJson() { return planSnapshotJson; }
    public void setPlanSnapshotJson(String value) { this.planSnapshotJson = value; }
    public String getReplayNamespace() { return replayNamespace; }
    public void setReplayNamespace(String value) { this.replayNamespace = value; }
    public String getProposedHost() { return proposedHost; }
    public void setProposedHost(String value) { this.proposedHost = value; }
    public boolean isDryRunOnly() { return dryRunOnly; }
    public void setDryRunOnly(boolean value) { this.dryRunOnly = value; }
    public boolean isRealProvisioningEnabled() { return realProvisioningEnabled; }
    public void setRealProvisioningEnabled(boolean value) { this.realProvisioningEnabled = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
