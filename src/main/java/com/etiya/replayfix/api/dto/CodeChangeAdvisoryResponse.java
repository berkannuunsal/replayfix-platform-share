package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeChangeAdvisoryResponse(
        UUID caseId,
        String advisoryMode,
        boolean llmUsed,
        String llmStatus,
        String status,
        String confidence,
        RecommendedCodeChange recommendedCodeChange,
        List<String> risks,
        List<String> missingEvidence,
        List<String> testSuggestions,
        boolean shouldProceedToPatch,
        String deterministicFallbackReason,
        Map<String, Object> safePromptSummary,
        Instant generatedAt
) {
    public CodeChangeAdvisoryResponse {
        risks = risks == null ? List.of() : List.copyOf(risks);
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
        testSuggestions = testSuggestions == null
                ? List.of()
                : List.copyOf(testSuggestions);
        deterministicFallbackReason = deterministicFallbackReason == null
                ? ""
                : deterministicFallbackReason;
        safePromptSummary = sanitizeBudgetMetadata(safePromptSummary);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    private static Map<String, Object> sanitizeBudgetMetadata(
            Map<String, Object> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>(values);
        if (!safe.containsKey("weeklyBudgetUsd")
                && safe.containsKey("monthlyBudgetUsd")) {
            safe.put("weeklyBudgetUsd", safe.get("monthlyBudgetUsd"));
        }
        safe.putIfAbsent("budgetPeriod", "WEEKLY");
        safe.remove("monthlyBudgetUsd");
        return Map.copyOf(safe);
    }
}
