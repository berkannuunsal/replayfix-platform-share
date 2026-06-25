package com.etiya.replaylab.api.dto;

import java.util.List;

public record ReplayEnvironmentComponentHintsRequest(
        List<ReplayEnvironmentComponentHintInput> componentHints,
        String notes
) {
    public ReplayEnvironmentComponentHintsRequest {
        componentHints = componentHints == null
                ? List.of()
                : List.copyOf(componentHints);
        notes = notes == null ? "" : notes;
    }
}
