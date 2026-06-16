package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;

public record KubernetesConnectivityCheck(
        boolean success,
        String currentContext,
        Map<String, Boolean> permissions,
        boolean writeChecksPerformed,
        List<String> warnings
) {
}
