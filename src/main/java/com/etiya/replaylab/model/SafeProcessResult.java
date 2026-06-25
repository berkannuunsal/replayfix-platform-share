package com.etiya.replaylab.model;

import java.time.Instant;

public record SafeProcessResult(
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        Integer exitCode,
        boolean timedOut,
        String output
) {
}
