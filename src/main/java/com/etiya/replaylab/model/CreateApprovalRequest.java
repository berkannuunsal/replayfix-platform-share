package com.etiya.replaylab.model;

public record CreateApprovalRequest(
        String actor,
        String comment
) {
}
