package com.etiya.replayfix.workflow;

import com.etiya.replayfix.domain.WorkflowStepStatus;

import java.util.UUID;

public record WorkflowStepExecutionResult(
        WorkflowStepStatus status,
        String evidenceType,
        String evidenceSource,
        UUID evidenceId,
        String resultSummary,
        String errorCategory,
        String errorMessage
) {
    public static WorkflowStepExecutionResult success(String evidenceType, String evidenceSource, UUID evidenceId, String resultSummary) {
        return new WorkflowStepExecutionResult(
                WorkflowStepStatus.SUCCESS,
                evidenceType,
                evidenceSource,
                evidenceId,
                resultSummary,
                null,
                null
        );
    }

    public static WorkflowStepExecutionResult skipped(String reason) {
        return new WorkflowStepExecutionResult(
                WorkflowStepStatus.SKIPPED,
                null,
                null,
                null,
                reason,
                null,
                null
        );
    }

    public static WorkflowStepExecutionResult failed(String errorCategory, String errorMessage) {
        return new WorkflowStepExecutionResult(
                WorkflowStepStatus.FAILED,
                null,
                null,
                null,
                null,
                errorCategory,
                errorMessage
        );
    }

    public static WorkflowStepExecutionResult retryWaiting(String errorCategory, String errorMessage) {
        return new WorkflowStepExecutionResult(
                WorkflowStepStatus.RETRY_WAITING,
                null,
                null,
                null,
                null,
                errorCategory,
                errorMessage
        );
    }
}
