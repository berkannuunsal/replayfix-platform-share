package com.etiya.replayfix.model;

public record ConfluencePlannedQuery(
        String purpose,
        String cql,
        int limit,
        int priority
) {
}
