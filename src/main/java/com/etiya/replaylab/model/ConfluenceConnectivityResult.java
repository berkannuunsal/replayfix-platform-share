package com.etiya.replaylab.model;

import java.util.List;

public record ConfluenceConnectivityResult(
        boolean success,
        boolean enabled,
        boolean baseUrlConfigured,
        boolean usernameConfigured,
        boolean tokenConfigured,
        Integer httpStatus,
        String authenticatedMode,
        int visibleSpaceSampleCount,
        List<String> warnings
) {
}
