package com.etiya.replayfix.model;

public record TraceCollectionFailure(
        String traceId,
        String category,
        Integer httpStatus,
        String message
) {
}
