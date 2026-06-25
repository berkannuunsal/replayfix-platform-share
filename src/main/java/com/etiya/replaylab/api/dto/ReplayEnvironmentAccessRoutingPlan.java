package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.Map;

public record ReplayEnvironmentAccessRoutingPlan(
        String mode,
        String proposedHost,
        String frontendPath,
        String backendPath,
        String backendInternalServiceUrl,
        String backendBrowserAccessibleUrl,
        boolean backendRequiresIngress,
        boolean corsRisk,
        boolean cookieDomainRisk,
        List<String> requiredCustomerUiConfigKeys,
        List<String> requiredBackendConfigKeys,
        List<String> blockers,
        Map<String, Object> ingressRoutePlan
) {
}
