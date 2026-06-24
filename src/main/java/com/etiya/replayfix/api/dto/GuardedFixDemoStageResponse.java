package com.etiya.replayfix.api.dto;

import java.util.List;

public record GuardedFixDemoStageResponse(
        String name,
        String status,
        String summary,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions
) {
    public GuardedFixDemoStageResponse {
        name = name == null ? "" : name;
        status = status == null ? "" : status;
        summary = summary == null ? "" : summary;
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
