package com.etiya.replaylab.model;

public record LokiQueryCandidate(
    int priority,
    String reason,
    String logQl
) {
}
