package com.etiya.replayfix.model;

import java.util.List;

public record AiConnectivityResult(
        boolean success,
        boolean enabled,
        String provider,
        String model,
        boolean modelConfigured,
        boolean authConfigured,
        Integer httpStatus,
        long latencyMs,
        List<String> warnings
) {}
