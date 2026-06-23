package com.etiya.replayfix.api.dto;

public record CreateReplayEnvironmentRequestResponse(
        ReplayEnvironmentRequestResponse request,
        ReplayEnvironmentPlanResponse plan
) {
}
