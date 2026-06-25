package com.etiya.replaylab.api.dto;

public record CreateReplayEnvironmentRequestResponse(
        ReplayEnvironmentRequestResponse request,
        ReplayEnvironmentPlanResponse plan
) {
}
