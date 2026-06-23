package com.etiya.replayfix.model;

import java.util.List;

public record AiConnectivityResult(
        boolean success,
        boolean enabled,
        String provider,
        String model,
        boolean modelConfigured,
        boolean authConfigured,
        boolean baseUrlConfigured,
        boolean tokenConfigured,
        boolean reachable,
        Integer httpStatus,
        long latencyMs,
        String sanitizedErrorMessage,
        String modelProfile,
        String effectiveModelName,
        int effectiveTimeoutSeconds,
        int effectiveMaxPromptChars,
        int effectiveMaxOutputTokens,
        boolean budgetTrackingEnabled,
        String budgetPeriod,
        double weeklyBudgetUsd,
        boolean estimatedUsageAvailable,
        int promptTokenCount,
        int completionTokenCount,
        int totalTokenCount,
        List<String> warnings
) {
    public AiConnectivityResult(
            boolean success,
            boolean enabled,
            String provider,
            String model,
            boolean modelConfigured,
            boolean authConfigured,
            boolean baseUrlConfigured,
            boolean tokenConfigured,
            boolean reachable,
            Integer httpStatus,
            long latencyMs,
            String sanitizedErrorMessage,
            List<String> warnings
    ) {
        this(
                success,
                enabled,
                provider,
                model,
                modelConfigured,
                authConfigured,
                baseUrlConfigured,
                tokenConfigured,
                reachable,
                httpStatus,
                latencyMs,
                sanitizedErrorMessage,
                "",
                model,
                0,
                0,
                0,
                false,
                "WEEKLY",
                200.0,
                false,
                0,
                0,
                0,
                warnings
        );
    }

    public AiConnectivityResult {
        modelProfile = modelProfile == null ? "" : modelProfile;
        effectiveModelName = effectiveModelName == null
                ? ""
                : effectiveModelName;
        budgetPeriod = budgetPeriod == null || budgetPeriod.isBlank()
                ? "WEEKLY"
                : budgetPeriod;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
