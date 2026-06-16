package com.etiya.replayfix.model;

import java.util.Map;
import java.util.UUID;

public record AiGenerationRequest(
        UUID caseId,
        String requestType,
        String systemPrompt,
        String userPrompt,
        String model,
        double temperature,
        int maxOutputChars,
        boolean structuredOutput,
        Map<String, String> metadata
) {}
