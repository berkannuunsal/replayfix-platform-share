package com.etiya.replayfix.model;

public record CreateApprovalRequest(
        String actor,
        String comment
) {
}
