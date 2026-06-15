package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.TempoTrace;

public interface TempoClient {
    TempoTrace getTrace(String traceId);
}
