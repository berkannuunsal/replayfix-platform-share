package com.etiya.replaylab.model;

public record ApprovalDecisionRequest(
        String actor,
        String comment
) {
}
