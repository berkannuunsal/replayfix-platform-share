package com.etiya.replaylab.model;

public record ConfluencePlannedQuery(
        String purpose,
        String cql,
        int limit,
        int priority
) {
}
