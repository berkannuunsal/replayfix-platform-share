package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentProvisioningDisabledResponse(
        ReplayEnvironmentRequestResponse request,
        String message,
        List<String> nextActions
) {
}
