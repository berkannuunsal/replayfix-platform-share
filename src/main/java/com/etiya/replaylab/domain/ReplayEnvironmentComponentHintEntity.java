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
@Table(name = "rf_replay_component_hint")
public class ReplayEnvironmentComponentHintEntity {
    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "jira_key", nullable = false, length = 100)
    private String jiraKey;

    @Column(name = "target_key", nullable = false, length = 150)
    private String targetKey;

    @Column(name = "component_key", nullable = false, length = 150)
    private String componentKey;

    @Column(name = "requested_mode", nullable = false, length = 50)
    private String requestedMode;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(columnDefinition = "text")
    private String notes;

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
    public String getComponentKey() { return componentKey; }
    public void setComponentKey(String value) { this.componentKey = value; }
    public String getRequestedMode() { return requestedMode; }
    public void setRequestedMode(String value) { this.requestedMode = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { this.notes = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
