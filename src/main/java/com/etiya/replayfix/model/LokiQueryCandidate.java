package com.etiya.replayfix.model;

public record LokiQueryCandidate(
    int priority,
    String reason,
    String logQl
) {
}
