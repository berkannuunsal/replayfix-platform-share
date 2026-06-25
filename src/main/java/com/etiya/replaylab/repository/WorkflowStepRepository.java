package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.WorkflowStepEntity;
import com.etiya.replaylab.domain.WorkflowStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStepEntity, UUID> {

    List<WorkflowStepEntity> findByWorkflowRunIdOrderByStartedAtAscIdAsc(UUID workflowRunId);

    List<WorkflowStepEntity> findByWorkflowRunIdAndStatus(UUID workflowRunId, WorkflowStepStatus status);
    
    void deleteByWorkflowRunId(UUID workflowRunId);
}
