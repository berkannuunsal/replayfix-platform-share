package com.etiya.replayfix.model;

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
                Map.of()
        );
    }

    public AiGenerationResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        outputPreview = outputPreview == null ? "" : outputPreview;
        responseShape = responseShape == null ? Map.of() : Map.copyOf(responseShape);
    }
}
