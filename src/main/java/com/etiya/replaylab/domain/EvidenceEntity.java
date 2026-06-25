package com.etiya.replaylab.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_evidence")
public class EvidenceEntity {
    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
        name = "evidence_type",
        nullable = false,
        length = 50
    )
    private EvidenceType evidenceType;

    @Column(nullable = false, length = 200)
    private String source;

    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    @Column(name = "content_location", length = 1000)
    private String contentLocation;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(columnDefinition = "text")
    private String body;

    @Column
    private Double confidence;

    @Column(nullable = false)
    private boolean sanitized;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID value) { this.id = value; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID value) { this.caseId = value; }
    public EvidenceType getEvidenceType() { return evidenceType; }
    public void setEvidenceType(EvidenceType value) { this.evidenceType = value; }
    public String getSource() { return source; }
    public void setSource(String value) { this.source = value; }
    public String getContentText() { return contentText; }
    public void setContentText(String value) { this.contentText = value; }
    public String getContentLocation() { return contentLocation; }
    public void setContentLocation(String value) { this.contentLocation = value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { this.contentHash = value; }
    public String getBody() { return body; }
    public void setBody(String value) { this.body = value; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double value) { this.confidence = value; }
    public boolean isSanitized() { return sanitized; }
    public void setSanitized(boolean value) { this.sanitized = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
}
