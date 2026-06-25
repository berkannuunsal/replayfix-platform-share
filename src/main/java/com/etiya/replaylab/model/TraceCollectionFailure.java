package com.etiya.replaylab.model;

public record TraceCollectionFailure(
        String traceId,
        String category,
        Integer httpStatus,
        String message
) {
}
