package com.etiya.replayfix.domain;

public enum WorkflowStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    SKIPPED,
    FAILED,
    RETRY_WAITING
}
