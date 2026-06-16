package com.etiya.replayfix.model;

public enum RuntimeVersionCorrelationStatus {
    EXACT_COMMIT_MATCH,
    IMAGE_TAG_BUILD_MATCH,
    IMAGE_DIGEST_ONLY,
    REPLICASET_TIME_SUPPORTS_BUILD,
    CURRENT_RUNTIME_ONLY,
    MISMATCH,
    INSUFFICIENT_EVIDENCE
}
