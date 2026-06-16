package com.etiya.replayfix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rf_approval_request")
public class ApprovalRequestEntity {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "target_type",
            nullable = false,
            length = 80
    )
    private ApprovalTargetType targetType;

    @Column(
            name = "target_evidence_id",
            nullable = false
    )
    private UUID targetEvidenceId;

    @Column(
            name = "target_evidence_type",
            nullable = false,
            length = 80
    )
    private String targetEvidenceType;

    @Column(
            name = "target_evidence_source",
            nullable = false,
            length = 200
    )
    private String targetEvidenceSource;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 40
    )
    private ApprovalStatus status;

    @Column(
            name = "requested_by",
            nullable = false,
            length = 200
    )
    private String requestedBy;

    @Column(
            name = "request_comment",
            length = 2000
    )
    private String requestComment;

    @Column(
            name = "requested_at",
            nullable = false
    )
    private Instant requestedAt;

    @Column(
            name = "decided_by",
            length = 200
    )
    private String decidedBy;

    @Column(
            name = "decision_comment",
            length = 4000
    )
    private String decisionComment;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    private long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public ApprovalTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(
            ApprovalTargetType targetType
    ) {
        this.targetType = targetType;
    }

    public UUID getTargetEvidenceId() {
        return targetEvidenceId;
    }

    public void setTargetEvidenceId(
            UUID targetEvidenceId
    ) {
        this.targetEvidenceId = targetEvidenceId;
    }

    public String getTargetEvidenceType() {
        return targetEvidenceType;
    }

    public void setTargetEvidenceType(
            String targetEvidenceType
    ) {
        this.targetEvidenceType = targetEvidenceType;
    }

    public String getTargetEvidenceSource() {
        return targetEvidenceSource;
    }

    public void setTargetEvidenceSource(
            String targetEvidenceSource
    ) {
        this.targetEvidenceSource =
                targetEvidenceSource;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public void setStatus(
            ApprovalStatus status
    ) {
        this.status = status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(
            String requestedBy
    ) {
        this.requestedBy = requestedBy;
    }

    public String getRequestComment() {
        return requestComment;
    }

    public void setRequestComment(
            String requestComment
    ) {
        this.requestComment = requestComment;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(
            Instant requestedAt
    ) {
        this.requestedAt = requestedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(
            String decidedBy
    ) {
        this.decidedBy = decidedBy;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public void setDecisionComment(
            String decisionComment
    ) {
        this.decisionComment = decisionComment;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(
            Instant decidedAt
    ) {
        this.decidedAt = decidedAt;
    }

    public long getVersion() {
        return version;
    }
}
