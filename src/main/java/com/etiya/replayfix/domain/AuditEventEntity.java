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

    public void setCaseId(UUID value) { this.caseId = value; }
    public void setAction(String value) { this.action = value; }
    public void setActor(String value) { this.actor = value; }
    public void setDetails(String value) { this.details = value; }
}
