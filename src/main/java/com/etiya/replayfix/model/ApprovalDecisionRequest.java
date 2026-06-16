package com.etiya.replayfix.model;

public record ApprovalDecisionRequest(
        String actor,
        String comment
) {
}
