package com.etiya.replayfix.model;

import java.util.List;

public record AiConnectivityResult(
        boolean success,
        boolean enabled,
        String provider,
        String model,
        boolean modelConfigured,
        boolean authConfigured,
        boolean baseUrlConfigured,
        boolean tokenConfigured,
        boolean reachable,
        Integer httpStatus,
        long latencyMs,
        String sanitizedErrorMessage,
        List<String> warnings
) {}
