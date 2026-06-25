package com.etiya.replaylab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rf_notification",
        indexes = {
                @Index(name = "idx_notification_case", columnList = "case_id"),
                @Index(name = "idx_notification_status", columnList = "status"),
                @Index(name = "idx_notification_created", columnList = "created_at")
        }
)
public class NotificationEntity {

    @Id
    private UUID id;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    @Column(name = "jira_key", length = 100)
    private String jiraKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private NotificationStatus status;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "text")
    private String message;

    @Column(length = 20)
    private String severity;

    @Column(name = "target_url", length = 1000)
    private String targetUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "delivery_attempt", nullable = false)
    private int deliveryAttempt = 0;

    @Column(name = "last_delivery_error", columnDefinition = "text")
    private String lastDeliveryError;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = NotificationStatus.UNREAD;
    }

    public UUID getId() { return id; }
    public void setId(UUID value) { this.id = value; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID value) { this.workflowRunId = value; }
    public String getJiraKey() { return jiraKey; }
    public void setJiraKey(String value) { this.jiraKey = value; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType value) { this.type = value; }
    public NotificationStatus getStatus() { return status; }
    public void setStatus(NotificationStatus value) { this.status = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { this.message = value; }
    public String getSeverity() { return severity; }
    public void setSeverity(String value) { this.severity = value; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String value) { this.targetUrl = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant value) { this.readAt = value; }
    public int getDeliveryAttempt() { return deliveryAttempt; }
    public void setDeliveryAttempt(int value) { this.deliveryAttempt = value; }
    public String getLastDeliveryError() { return lastDeliveryError; }
    public void setLastDeliveryError(String value) { this.lastDeliveryError = value; }
    public long getVersion() { return version; }
}
