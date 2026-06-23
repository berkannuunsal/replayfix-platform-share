package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeChangeAdvisoryResultSummary(
        UUID advisoryId,
        String advisoryMode,
        String filePath,
        String classOrComponentName,
        String methodName,
        String language,
        boolean llmUsed,
        String llmStatus,
        String status,
        String confidence,
        String changeType,
        boolean shouldProceedToPatch,
        int riskCount,
        int missingEvidenceCount,
        int testSuggestionCount,
        RecommendedCodeChange recommendedCodeChange,
        List<String> risks,
        List<String> missingEvidence,
        List<String> testSuggestions,
        String deterministicFallbackReason,
        Map<String, Object> safePromptSummary,
        String provider,
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
        boolean hydratedFromSource,
        String sourceCandidateSource,
        String repositoryLogicalName,
        String normalizedFilePath,
        int snippetChars,
        List<String> hydrationWarnings,
        List<String> hydrationMissingEvidence,
        Instant createdAt
) {
    public CodeChangeAdvisoryResultSummary(
            UUID advisoryId,
            String advisoryMode,
            String filePath,
            String classOrComponentName,
            String methodName,
            String language,
            boolean llmUsed,
            String llmStatus,
            String status,
            String confidence,
            String changeType,
            boolean shouldProceedToPatch,
            int riskCount,
            int missingEvidenceCount,
            int testSuggestionCount,
            RecommendedCodeChange recommendedCodeChange,
            List<String> risks,
            List<String> missingEvidence,
            List<String> testSuggestions,
            String deterministicFallbackReason,
            Map<String, Object> safePromptSummary,
            Instant createdAt
    ) {
        this(
                advisoryId,
                advisoryMode,
                filePath,
                classOrComponentName,
                methodName,
                language,
                llmUsed,
                llmStatus,
                status,
                confidence,
                changeType,
                shouldProceedToPatch,
                riskCount,
                missingEvidenceCount,
                testSuggestionCount,
                recommendedCodeChange,
                risks,
                missingEvidence,
                testSuggestions,
                deterministicFallbackReason,
                safePromptSummary,
                stringValue(safePromptSummary, "provider"),
                stringValue(safePromptSummary, "modelProfile"),
                stringValue(safePromptSummary, "effectiveModelName"),
                intValue(safePromptSummary, "effectiveTimeoutSeconds"),
                intValue(safePromptSummary, "effectiveMaxPromptChars"),
                intValue(safePromptSummary, "effectiveMaxOutputTokens"),
                booleanValue(safePromptSummary, "budgetTrackingEnabled"),
                stringValue(safePromptSummary, "budgetPeriod"),
                budgetUsdValue(safePromptSummary),
                booleanValue(safePromptSummary, "estimatedUsageAvailable"),
                intValue(safePromptSummary, "promptTokenCount"),
                intValue(safePromptSummary, "completionTokenCount"),
                intValue(safePromptSummary, "totalTokenCount"),
                booleanValue(safePromptSummary, "hydratedFromSource"),
                stringValue(safePromptSummary, "sourceCandidateSource"),
                stringValue(safePromptSummary, "repositoryLogicalName"),
                stringValue(safePromptSummary, "normalizedFilePath"),
                intValue(safePromptSummary, "snippetChars"),
                listValue(safePromptSummary, "hydrationWarnings"),
                listValue(safePromptSummary, "hydrationMissingEvidence"),
                createdAt
        );
    }

    public CodeChangeAdvisoryResultSummary(
            UUID advisoryId,
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
            Instant createdAt
    ) {
        this(
                advisoryId,
                advisoryMode,
                stringValue(safePromptSummary, "filePath"),
                stringValue(safePromptSummary, "classOrComponentName"),
                recommendedCodeChange == null
                        ? stringValue(safePromptSummary, "methodName")
                        : recommendedCodeChange.methodName(),
                stringValue(safePromptSummary, "language"),
                llmUsed,
                llmStatus,
                status,
                confidence,
                recommendedCodeChange == null
                        ? ""
                        : recommendedCodeChange.changeType(),
                shouldProceedToPatch,
                risks == null ? 0 : risks.size(),
                missingEvidence == null ? 0 : missingEvidence.size(),
                testSuggestions == null ? 0 : testSuggestions.size(),
                recommendedCodeChange,
                risks,
                missingEvidence,
                testSuggestions,
                deterministicFallbackReason,
                safePromptSummary,
                stringValue(safePromptSummary, "provider"),
                stringValue(safePromptSummary, "modelProfile"),
                stringValue(safePromptSummary, "effectiveModelName"),
                intValue(safePromptSummary, "effectiveTimeoutSeconds"),
                intValue(safePromptSummary, "effectiveMaxPromptChars"),
                intValue(safePromptSummary, "effectiveMaxOutputTokens"),
                booleanValue(safePromptSummary, "budgetTrackingEnabled"),
                stringValue(safePromptSummary, "budgetPeriod"),
                budgetUsdValue(safePromptSummary),
                booleanValue(safePromptSummary, "estimatedUsageAvailable"),
                intValue(safePromptSummary, "promptTokenCount"),
                intValue(safePromptSummary, "completionTokenCount"),
                intValue(safePromptSummary, "totalTokenCount"),
                booleanValue(safePromptSummary, "hydratedFromSource"),
                stringValue(safePromptSummary, "sourceCandidateSource"),
                stringValue(safePromptSummary, "repositoryLogicalName"),
                stringValue(safePromptSummary, "normalizedFilePath"),
                intValue(safePromptSummary, "snippetChars"),
                listValue(safePromptSummary, "hydrationWarnings"),
                listValue(safePromptSummary, "hydrationMissingEvidence"),
                createdAt
        );
    }

    public CodeChangeAdvisoryResultSummary(
            UUID advisoryId,
            String advisoryMode,
            String filePath,
            String classOrComponentName,
            String methodName,
            String language,
            boolean llmUsed,
            String llmStatus,
            String status,
            String confidence,
            String changeType,
            boolean shouldProceedToPatch,
            int riskCount,
            int missingEvidenceCount,
            int testSuggestionCount,
            RecommendedCodeChange recommendedCodeChange,
            List<String> risks,
            List<String> missingEvidence,
            List<String> testSuggestions,
            String deterministicFallbackReason,
            Map<String, Object> safePromptSummary,
            boolean hydratedFromSource,
            String sourceCandidateSource,
            String repositoryLogicalName,
            String normalizedFilePath,
            int snippetChars,
            List<String> hydrationWarnings,
            List<String> hydrationMissingEvidence,
            Instant createdAt
    ) {
        this(
                advisoryId,
                advisoryMode,
                filePath,
                classOrComponentName,
                methodName,
                language,
                llmUsed,
                llmStatus,
                status,
                confidence,
                changeType,
                shouldProceedToPatch,
                riskCount,
                missingEvidenceCount,
                testSuggestionCount,
                recommendedCodeChange,
                risks,
                missingEvidence,
                testSuggestions,
                deterministicFallbackReason,
                safePromptSummary,
                stringValue(safePromptSummary, "provider"),
                stringValue(safePromptSummary, "modelProfile"),
                stringValue(safePromptSummary, "effectiveModelName"),
                intValue(safePromptSummary, "effectiveTimeoutSeconds"),
                intValue(safePromptSummary, "effectiveMaxPromptChars"),
                intValue(safePromptSummary, "effectiveMaxOutputTokens"),
                booleanValue(safePromptSummary, "budgetTrackingEnabled"),
                stringValue(safePromptSummary, "budgetPeriod"),
                budgetUsdValue(safePromptSummary),
                booleanValue(safePromptSummary, "estimatedUsageAvailable"),
                intValue(safePromptSummary, "promptTokenCount"),
                intValue(safePromptSummary, "completionTokenCount"),
                intValue(safePromptSummary, "totalTokenCount"),
                hydratedFromSource,
                sourceCandidateSource,
                repositoryLogicalName,
                normalizedFilePath,
                snippetChars,
                hydrationWarnings,
                hydrationMissingEvidence,
                createdAt
        );
    }

    public CodeChangeAdvisoryResultSummary {
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
        provider = provider == null ? "" : provider;
        modelProfile = modelProfile == null ? "" : modelProfile;
        effectiveModelName = effectiveModelName == null
                ? ""
                : effectiveModelName;
        budgetPeriod = budgetPeriod == null || budgetPeriod.isBlank()
                ? "WEEKLY"
                : budgetPeriod;
        sourceCandidateSource = sourceCandidateSource == null
                ? ""
                : sourceCandidateSource;
        repositoryLogicalName = repositoryLogicalName == null
                ? ""
                : repositoryLogicalName;
        normalizedFilePath = normalizedFilePath == null
                ? ""
                : normalizedFilePath;
        hydrationWarnings = hydrationWarnings == null
                ? List.of()
                : List.copyOf(hydrationWarnings);
        hydrationMissingEvidence = hydrationMissingEvidence == null
                ? List.of()
                : List.copyOf(hydrationMissingEvidence);
    }

    private static String stringValue(
            Map<String, Object> values,
            String key
    ) {
        if (values == null || !values.containsKey(key)) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanValue(
            Map<String, Object> values,
            String key
    ) {
        if (values == null || !values.containsKey(key)) {
            return false;
        }
        Object value = values.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Map<String, Object> values, String key) {
        if (values == null || !values.containsKey(key)) {
            return 0;
        }
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double doubleValue(Map<String, Object> values, String key) {
        if (values == null || !values.containsKey(key)) {
            return 0.0;
        }
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static double budgetUsdValue(Map<String, Object> values) {
        double weekly = doubleValue(values, "weeklyBudgetUsd");
        if (weekly > 0.0) {
            return weekly;
        }
        return doubleValue(values, "monthlyBudgetUsd");
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

    private static List<String> listValue(
            Map<String, Object> values,
            String key
    ) {
        if (values == null || !values.containsKey(key)) {
            return List.of();
        }
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .toList();
        }
        return List.of();
    }
}
