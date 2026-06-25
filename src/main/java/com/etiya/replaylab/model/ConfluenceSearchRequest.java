package com.etiya.replaylab.model;

public record ConfluenceSearchRequest(
        String cql,
        int limit
) {
}
