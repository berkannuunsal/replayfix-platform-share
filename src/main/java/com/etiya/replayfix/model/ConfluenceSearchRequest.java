package com.etiya.replayfix.model;

public record ConfluenceSearchRequest(
        String cql,
        int limit
) {
}
