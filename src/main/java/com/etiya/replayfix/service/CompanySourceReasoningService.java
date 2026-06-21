package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;
import com.etiya.replayfix.model.SourceRecentCommit;
import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChange;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CompanySourceReasoningService {

    public static final String COMPANY_LLM_UNAVAILABLE =
            "COMPANY_LLM_UNAVAILABLE";
    public static final String COMPANY_LLM_INVALID_RESPONSE =
            "COMPANY_LLM_INVALID_RESPONSE";
    public static final String COMPANY_LLM_TIMEOUT =
            "COMPANY_LLM_TIMEOUT";
    public static final String COMPANY_LLM_EMPTY_RESPONSE =
            "COMPANY_LLM_EMPTY_RESPONSE";
    public static final String PARSE_EMPTY_RESPONSE = "EMPTY_RESPONSE";
    public static final String PARSE_NON_JSON_RESPONSE = "NON_JSON_RESPONSE";
    public static final String PARSE_UNKNOWN = "UNKNOWN";

    private final ReplayFixProperties properties;
    private final AiProviderClientFactory aiProviderClientFactory;
    private final ObjectMapper objectMapper;

    public CompanySourceReasoningService(
            ReplayFixProperties properties,
            AiProviderClientFactory aiProviderClientFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.aiProviderClientFactory = aiProviderClientFactory;
        this.objectMapper = objectMapper;
    }

    public ReasoningResult reason(
            UUID caseId,
            SourceReasoningContext context
    ) {
        try {
            return reason(caseId, objectMapper.writeValueAsString(context));
        } catch (Exception ignored) {
            return unavailable();
        }
    }

    public ReasoningResult reason(
            UUID caseId,
            String contextJson
    ) {
        return reason(caseId, contextJson, 500, "COMPACT");
    }

    public ReasoningResult reason(
            UUID caseId,
            String contextJson,
            int maxOutputTokens,
            String contextMode
    ) {
        int effectiveOutputTokenLimit = Math.max(1, maxOutputTokens);
        if (!properties.getAi().isEnabled()
                || properties.getAi().getProvider() != AiProviderType.COMPANY_LLM) {
            return unavailable(effectiveOutputTokenLimit);
        }

        try {
            AiProviderClient provider = aiProviderClientFactory.getProvider();
            AiGenerationResponse response = provider.generate(new AiGenerationRequest(
                    caseId,
                    "SOURCE_CHANGE_ANALYSIS",
                    systemPrompt(contextMode),
                    userPrompt(contextJson, contextMode),
                    properties.getAi().getCompany().getModel(),
                    properties.getAi().getTemperature(),
                    effectiveOutputTokenLimit,
                    true,
                    Map.of(
                            "requestType", "SOURCE_CHANGE_ANALYSIS",
                            "contextMode", contextMode == null
                                    ? "COMPACT"
                                    : contextMode
                    )
            ));

            if (!response.success()) {
                return failed(
                        response.errorCategory(),
                        response.warnings(),
                        response.parseErrorCategory(),
                        response.outputPreview(),
                        firstPositive(
                                response.effectiveOutputTokenLimit(),
                                effectiveOutputTokenLimit
                        )
                );
            }
            if (response.structuredResponse() == null) {
                return invalidResponse(
                        response.warnings(),
                        PARSE_UNKNOWN,
                        "",
                        effectiveOutputTokenLimit
                );
            }

            return parse(
                    response.structuredResponse(),
                    response.warnings(),
                    firstPositive(
                            response.effectiveOutputTokenLimit(),
                            effectiveOutputTokenLimit
                    )
            );
        } catch (Exception ignored) {
            return unavailable(effectiveOutputTokenLimit);
        }
    }

    private ReasoningResult parse(
            JsonNode node,
            List<String> providerWarnings,
            int effectiveOutputTokenLimit
    ) {
        List<String> warnings = new ArrayList<>(providerWarnings);
        String status = "HYPOTHESIS";
        double confidence = clamp(node.path("confidence").asDouble(0.0));
        List<SourceSuspectChange> suspectChanges = new ArrayList<>();

        JsonNode changes = node.path("suspectChanges");
        if (changes.isArray()) {
            for (JsonNode change : changes) {
                suspectChanges.add(new SourceSuspectChange(
                        change.path("file").asText(""),
                        change.path("className").asText(null),
                        change.path("methodName").asText(null),
                        change.path("layer").asText("UNKNOWN"),
                        change.path("relatedFlow").asText(""),
                        strings(change.path("relatedSignals")),
                        change.path("recentCommitCount").asInt(0),
                        List.of(),
                        change.path("suspectReason").asText(""),
                        clamp(change.path("confidence").asDouble(0.0)),
                        "HYPOTHESIS",
                        strings(change.path("warnings"))
                ));
            }
        }

        return new ReasoningResult(
                true,
                suspectChanges,
                status,
                confidence,
                strings(node.path("facts")),
                strings(node.path("inferences")),
                strings(node.path("unknowns")),
                strings(node.path("missingEvidence")),
                node.path("recommendedNextAction").asText(""),
                warnings,
                null,
                "",
                effectiveOutputTokenLimit
        );
    }

    private ReasoningResult unavailable() {
        return unavailable(0);
    }

    private ReasoningResult unavailable(int effectiveOutputTokenLimit) {
        return unavailable(List.of(), effectiveOutputTokenLimit);
    }

    private ReasoningResult unavailable(
            List<String> providerWarnings,
            int effectiveOutputTokenLimit
    ) {
        return new ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                warningsWith(providerWarnings, COMPANY_LLM_UNAVAILABLE),
                null,
                "",
                effectiveOutputTokenLimit
        );
    }

    private ReasoningResult invalidResponse() {
        return invalidResponse(List.of(), PARSE_UNKNOWN, "", 0);
    }

    private ReasoningResult invalidResponse(
            List<String> providerWarnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit
    ) {
        return new ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                warningsWith(providerWarnings, COMPANY_LLM_INVALID_RESPONSE),
                normalizedParseErrorCategory(parseErrorCategory),
                safeOutputPreview(outputPreview),
                effectiveOutputTokenLimit
        );
    }

    private ReasoningResult timeout() {
        return timeout(List.of(), 0);
    }

    private ReasoningResult timeout(
            List<String> providerWarnings,
            int effectiveOutputTokenLimit
    ) {
        return new ReasoningResult(
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                warningsWith(providerWarnings, COMPANY_LLM_TIMEOUT),
                null,
                "",
                effectiveOutputTokenLimit
        );
    }

    private ReasoningResult failed(String errorCategory) {
        return failed(errorCategory, List.of(), null, "", 0);
    }

    private ReasoningResult failed(
            String errorCategory,
            List<String> providerWarnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit
    ) {
        if ("INVALID_JSON".equalsIgnoreCase(errorCategory)) {
            return invalidResponse(
                    providerWarnings,
                    firstNonBlank(parseErrorCategory, PARSE_NON_JSON_RESPONSE),
                    outputPreview,
                    effectiveOutputTokenLimit
            );
        }
        if ("EMPTY_RESPONSE".equalsIgnoreCase(errorCategory)) {
            return invalidResponse(warningsWith(
                    providerWarnings,
                    COMPANY_LLM_EMPTY_RESPONSE
            ), PARSE_EMPTY_RESPONSE, outputPreview, effectiveOutputTokenLimit);
        }
        if ("TIMEOUT".equalsIgnoreCase(errorCategory)) {
            return timeout(providerWarnings, effectiveOutputTokenLimit);
        }
        if (errorCategory != null
                && errorCategory.toUpperCase(java.util.Locale.ROOT)
                .startsWith("HTTP_503")) {
            return unavailable(providerWarnings, effectiveOutputTokenLimit);
        }
        return unavailable(providerWarnings, effectiveOutputTokenLimit);
    }

    private List<String> warningsWith(
            List<String> providerWarnings,
            String... additionalWarnings
    ) {
        Set<String> warnings = new LinkedHashSet<>();
        if (providerWarnings != null) {
            providerWarnings.stream()
                    .filter(warning -> warning != null && !warning.isBlank())
                    .forEach(warnings::add);
        }
        for (String warning : additionalWarnings) {
            if (warning != null && !warning.isBlank()) {
                warnings.add(warning);
            }
        }
        return List.copyOf(warnings);
    }

    private String normalizedParseErrorCategory(String value) {
        if (value == null || value.isBlank()) {
            return PARSE_UNKNOWN;
        }
        return switch (value) {
            case "EMPTY_RESPONSE",
                    "NON_JSON_RESPONSE",
                    "SCHEMA_MISMATCH",
                    "JSON_EXTRACTION_FAILED",
                    "UNKNOWN" -> value;
            default -> PARSE_UNKNOWN;
        };
    }

    private String safeOutputPreview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value
                .replaceAll("(?i)(\"(?:authorization|cookie|token|password)\"\\s*:\\s*\")([^\"]+)(\")", "$1[REDACTED]$3")
                .replaceAll("(?i)(authorization|cookie|token|password)\\s*[:=]\\s*(bearer\\s+)?[^\\s,;]+", "[REDACTED]")
                .replaceAll("(?im)^\\s*at\\s+[\\w.$]+\\([^\\r\\n]*\\)\\s*$", "")
                .replaceAll("(?is)reasoning_content\\s*[:=].*", "[REDACTED_REASONING_CONTENT]")
                .trim();
        return sanitized.length() <= 500
                ? sanitized
                : sanitized.substring(0, 500);
    }

    private int firstPositive(int first, int second) {
        return first > 0 ? first : Math.max(0, second);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String systemPrompt(String contextMode) {
        if ("MINIMAL".equalsIgnoreCase(contextMode)) {
            return """
                    Return only compact JSON.
                    Do not explain.
                    Do not include markdown.
                    Do not include reasoning.
                    Do not include code blocks.
                    Do not mark anything CONFIRMED.
                    """;
        }
        return """
                You are ReplayFix source reasoning AI. Return valid JSON only.
                Use only supplied evidence. Separate FACT, INFERENCE and UNKNOWN.
                Do not invent files, methods, commits or logs. Status must remain
                HYPOTHESIS unless supplied replay/test/log evidence confirms.
                """;
    }

    private String userPrompt(String contextJson, String contextMode) {
        if ("MINIMAL".equalsIgnoreCase(contextMode)) {
            return """
                    Given this small ReplayFix evidence packet, produce only:
                    {
                      "status": "HYPOTHESIS",
                      "confidence": 0.0,
                      "suspectReason": "",
                      "recommendedNextAction": "",
                      "facts": [],
                      "inferences": [],
                      "unknowns": [],
                      "warnings": []
                    }

                    Packet:
                    %s
                    """.formatted(contextJson);
        }
        return """
                Analyze this bounded ReplayFix source reasoning context.

                Return valid JSON only:
                {
                  "status": "HYPOTHESIS",
                  "confidence": 0.0,
                  "suspectChanges": [
                    {
                      "file": "",
                      "className": "",
                      "methodName": "",
                      "layer": "",
                      "relatedFlow": "",
                      "relatedSignals": [],
                      "recentCommitCount": 0,
                      "recentCommits": [],
                      "suspectReason": "",
                      "confidence": 0.0,
                      "status": "HYPOTHESIS",
                      "warnings": []
                    }
                  ],
                  "facts": [],
                  "inferences": [],
                  "unknowns": [],
                  "missingEvidence": [],
                  "recommendedNextAction": "",
                  "warnings": []
                }

                Context:
                %s
                """.formatted(contextJson);
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
            return values;
        }
        if (node.isTextual()) {
            values.add(node.asText());
        }
        return values;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record ReasoningResult(
            boolean llmUsed,
            List<SourceSuspectChange> suspectChanges,
            String status,
            double confidence,
            List<String> facts,
            List<String> inferences,
            List<String> unknowns,
            List<String> missingEvidence,
            String recommendedNextAction,
            List<String> warnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit
    ) {
        public ReasoningResult(
                boolean llmUsed,
                List<SourceSuspectChange> suspectChanges,
                String status,
                double confidence,
                List<String> facts,
                List<String> inferences,
                List<String> unknowns,
                List<String> missingEvidence,
                String recommendedNextAction,
                List<String> warnings
        ) {
            this(
                    llmUsed,
                    suspectChanges,
                    status,
                    confidence,
                    facts,
                    inferences,
                    unknowns,
                    missingEvidence,
                    recommendedNextAction,
                    warnings,
                    null,
                    "",
                    0
            );
        }
    }
}
