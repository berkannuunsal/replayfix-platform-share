package com.etiya.replaylab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rf_workflow_run",
        indexes = {
                @Index(name = "idx_workflow_run_case", columnList = "case_id"),
                @Index(name = "idx_workflow_run_status", columnList = "status"),
                @Index(name = "idx_workflow_run_created", columnList = "created_at")
        }
)
public class WorkflowRunEntity {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    @Column(name = "trigger_reference", length = 200)
    private String triggerReference;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private WorkflowRunStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "successful_step_count", nullable = false)
    private int successfulStepCount = 0;

    @Column(name = "failed_step_count", nullable = false)
    private int failedStepCount = 0;

    @Column(name = "skipped_step_count", nullable = false)
    private int skippedStepCount = 0;

    @Column(length = 500)
    private String summary;

    @Column(name = "jira_preview_evidence_id")
    private UUID jiraPreviewEvidenceId;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String value) { this.triggerType = value; }
    public String getTriggerReference() { return triggerReference; }
    public void setTriggerReference(String value) { this.triggerReference = value; }
    public WorkflowRunStatus getStatus() { return status; }
    public void setStatus(WorkflowRunStatus value) { this.status = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { this.startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { this.finishedAt = value; }
    public int getSuccessfulStepCount() { return successfulStepCount; }
    public void setSuccessfulStepCount(int value) { this.successfulStepCount = value; }
    public int getFailedStepCount() { return failedStepCount; }
    public void setFailedStepCount(int value) { this.failedStepCount = value; }
    public int getSkippedStepCount() { return skippedStepCount; }
    public void setSkippedStepCount(int value) { this.skippedStepCount = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { this.summary = value; }
    public UUID getJiraPreviewEvidenceId() { return jiraPreviewEvidenceId; }
    public void setJiraPreviewEvidenceId(UUID value) { this.jiraPreviewEvidenceId = value; }
    public long getVersion() { return version; }
}
