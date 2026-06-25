package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.ApprovalRequestEntity;
import com.etiya.replaylab.domain.ApprovalStatus;
import com.etiya.replaylab.domain.ApprovalTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository
        extends JpaRepository<ApprovalRequestEntity, UUID> {

    List<ApprovalRequestEntity>
    findByCaseIdOrderByRequestedAtDesc(
            UUID caseId
    );

    Optional<ApprovalRequestEntity>
    findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusOrderByRequestedAtDesc(
            UUID caseId,
            ApprovalTargetType targetType,
            UUID targetEvidenceId,
            ApprovalStatus status
    );

    Optional<ApprovalRequestEntity>
    findFirstByCaseIdAndTargetTypeAndTargetEvidenceIdAndStatusInOrderByRequestedAtDesc(
            UUID caseId,
            ApprovalTargetType targetType,
            UUID targetEvidenceId,
            List<ApprovalStatus> statuses
    );
}
