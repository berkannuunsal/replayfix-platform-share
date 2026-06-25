package com.etiya.replaylab.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rf_webhook_delivery",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "delivery_id"}),
        indexes = {
                @Index(name = "idx_webhook_delivery_issue", columnList = "issue_key"),
                @Index(name = "idx_webhook_delivery_case", columnList = "case_id")
        }
)
public class WebhookDeliveryEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "delivery_id", nullable = false, length = 200)
    private String deliveryId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "issue_key", nullable = false, length = 100)
    private String issueKey;

    @Column(name = "body_sha256", nullable = false, length = 64)
    private String bodySha256;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        receivedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { this.provider = value; }
    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String value) { this.deliveryId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { this.eventType = value; }
    public String getIssueKey() { return issueKey; }
    public void setIssueKey(String value) { this.issueKey = value; }
    public String getBodySha256() { return bodySha256; }
    public void setBodySha256(String value) { this.bodySha256 = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID value) { this.workflowRunId = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { this.completedAt = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { this.errorMessage = value; }
    public long getVersion() { return version; }
}
