package com.etiya.replaylab.api.dto;

import java.util.List;

public record ReplayLabTokenUsageEstimateResponse(
        String mode,
        int promptTokens,
        int completionTokens,
        int evidenceContextTokens,
        int rcaGenerationTokens,
        int rovoEnrichmentTokens,
        int prPreflightTokens,
        int totalEstimatedTokens,
        List<String> notes
) {
}
