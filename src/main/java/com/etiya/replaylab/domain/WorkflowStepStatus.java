package com.etiya.replaylab.domain;

public enum WorkflowStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    SKIPPED,
    FAILED,
    RETRY_WAITING
}
