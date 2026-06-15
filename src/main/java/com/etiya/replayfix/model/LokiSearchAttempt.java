package com.etiya.replayfix.model;

public record LokiSearchAttempt(
    int priority,
    String reason,
    String logQl,
    int resultCount,
    String error
) {
}
