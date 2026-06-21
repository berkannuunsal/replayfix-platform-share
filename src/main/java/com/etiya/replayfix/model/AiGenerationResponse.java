package com.etiya.replayfix.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

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
        int effectiveOutputTokenLimit
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
                0
        );
    }
}
