package com.etiya.replayfix.model;

import java.util.List;

public record TempoRawTrace(
        String traceId,
        int httpStatus,
        String contentType,
        String rawJson,
        boolean found,
        List<String> warnings
) {
}
