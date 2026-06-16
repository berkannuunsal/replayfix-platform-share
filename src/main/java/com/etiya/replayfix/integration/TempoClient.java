package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.TempoConnectivityResult;
import com.etiya.replayfix.model.TempoRawTrace;
import com.etiya.replayfix.model.TempoTraceResult;

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
