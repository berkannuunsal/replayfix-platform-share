package com.etiya.replayfix.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_audit_event")
public class AuditEventEntity {
    @Id
    private UUID id;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(nullable = false, length = 150)
    private String action;

    @Column(nullable = false, length = 150)
    private String actor;

    @Column(columnDefinition = "text")
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { this.action = value; }
    public String getActor() { return actor; }
    public void setActor(String value) { this.actor = value; }
    public String getDetails() { return details; }
    public void setDetails(String value) { this.details = value; }
    public Instant getCreatedAt() { return createdAt; }
}
