package com.etiya.replaylab.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record AiGenerationResponse(
        boolean success,
        String provider,
        String model,
        String requestId,
        String finishReason,
        long latencyMs,
        int inputCharacters,
        int outputCharacters,
        JsonNode structuredResponse,
        List<String> warnings,
        String errorCategory,
        String errorMessage,
        String parseErrorCategory,
        String outputPreview,
        int effectiveOutputTokenLimit,
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
        Map<String, Object> responseShape
) {
    public AiGenerationResponse(
            boolean success,
            String provider,
            String model,
            String requestId,
            String finishReason,
            long latencyMs,
            int inputCharacters,
            int outputCharacters,
            JsonNode structuredResponse,
            List<String> warnings,
            String errorCategory,
            String errorMessage
    ) {
        this(
                success,
                provider,
                model,
                requestId,
                finishReason,
                latencyMs,
                inputCharacters,
                outputCharacters,
                structuredResponse,
                warnings,
                errorCategory,
                errorMessage,
                null,
                "",
                0,
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
                Map.of()
        );
    }

    public AiGenerationResponse(
            boolean success,
            String provider,
            String model,
            String requestId,
            String finishReason,
            long latencyMs,
            int inputCharacters,
            int outputCharacters,
            JsonNode structuredResponse,
            List<String> warnings,
            String errorCategory,
            String errorMessage,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit
    ) {
        this(
                success,
                provider,
                model,
                requestId,
                finishReason,
                latencyMs,
                inputCharacters,
                outputCharacters,
                structuredResponse,
                warnings,
                errorCategory,
                errorMessage,
                parseErrorCategory,
                outputPreview,
                effectiveOutputTokenLimit,
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
                Map.of()
        );
    }

    public AiGenerationResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        outputPreview = outputPreview == null ? "" : outputPreview;
        modelProfile = modelProfile == null ? "" : modelProfile;
        effectiveModelName = effectiveModelName == null
                ? ""
                : effectiveModelName;
        budgetPeriod = budgetPeriod == null || budgetPeriod.isBlank()
                ? "WEEKLY"
                : budgetPeriod;
        responseShape = responseShape == null ? Map.of() : Map.copyOf(responseShape);
    }
}
