package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.WorkflowRunEntity;
import com.etiya.replaylab.domain.WorkflowRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    List<WorkflowRunEntity> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    Optional<WorkflowRunEntity> findFirstByCaseIdOrderByCreatedAtDesc(UUID caseId);

    Optional<WorkflowRunEntity> findFirstByCaseIdAndStatusInOrderByCreatedAtDesc(
            UUID caseId,
            List<WorkflowRunStatus> statuses
    );

    boolean existsByCaseIdAndStatusIn(UUID caseId, List<WorkflowRunStatus> statuses);
    
    void deleteByCaseId(UUID caseId);
}
