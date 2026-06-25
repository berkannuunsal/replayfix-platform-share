package com.etiya.replaylab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rf_workflow_step",
        indexes = {
                @Index(name = "idx_workflow_step_run", columnList = "workflow_run_id"),
                @Index(name = "idx_workflow_step_status", columnList = "status"),
                @Index(name = "idx_workflow_step_retry", columnList = "next_retry_at")
        }
)
public class WorkflowStepEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    @Column(name = "step_name", nullable = false, length = 100)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private WorkflowStepStatus status;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "evidence_type", length = 50)
    private String evidenceType;

    @Column(name = "evidence_source", length = 200)
    private String evidenceSource;

    @Column(name = "evidence_id")
    private UUID evidenceId;

    @Column(name = "error_category", length = 100)
    private String errorCategory;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID value) { this.workflowRunId = value; }
    public Integer getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Integer value) { this.sequenceNumber = value; }
    public String getStepName() { return stepName; }
    public void setStepName(String value) { this.stepName = value; }
    public WorkflowStepStatus getStatus() { return status; }
    public void setStatus(WorkflowStepStatus value) { this.status = value; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int value) { this.attempt = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { this.maxAttempts = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { this.startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { this.finishedAt = value; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant value) { this.nextRetryAt = value; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String value) { this.evidenceType = value; }
    public String getEvidenceSource() { return evidenceSource; }
    public void setEvidenceSource(String value) { this.evidenceSource = value; }
    public UUID getEvidenceId() { return evidenceId; }
    public void setEvidenceId(UUID value) { this.evidenceId = value; }
    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String value) { this.errorCategory = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { this.errorMessage = value; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String value) { this.resultSummary = value; }
    public long getVersion() { return version; }
}
