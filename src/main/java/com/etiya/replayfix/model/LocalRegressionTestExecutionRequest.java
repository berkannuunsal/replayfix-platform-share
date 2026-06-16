package com.etiya.replayfix.model;

import java.util.UUID;

public record LocalRegressionTestExecutionRequest(
        UUID approvalId
) {
}
