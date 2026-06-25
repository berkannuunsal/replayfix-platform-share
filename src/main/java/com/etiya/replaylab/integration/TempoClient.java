package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.TempoConnectivityResult;
import com.etiya.replaylab.model.TempoRawTrace;
import com.etiya.replaylab.model.TempoTraceResult;

import java.time.Instant;

public interface TempoClient {
    TempoTraceResult getTrace(
            String traceId,
            Instant start,
            Instant end
    );

    TempoRawTrace fetchTrace(String traceId);

    TempoConnectivityResult connectivity();
}
