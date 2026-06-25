package com.etiya.replaylab.model;

public record LokiSearchAttempt(
    int priority,
    String reason,
    String logQl,
    int resultCount,
    String error
) {
}
