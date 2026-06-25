package com.etiya.replaylab.model;

import java.util.List;

public record TempoConnectivityResult(
        boolean success,
        String accessMode,
        boolean baseUrlConfigured,
        boolean datasourceUidConfigured,
        boolean tokenConfigured,
        Integer httpStatus,
        String contentType,
        String endpointCategory,
        List<String> warnings
) {
}
