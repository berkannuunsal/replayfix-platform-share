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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CompanySourceReasoningService {

    public static final String COMPANY_LLM_UNAVAILABLE =
            "COMPANY_LLM_UNAVAILABLE";
    public static final String COMPANY_LLM_INVALID_RESPONSE =
            "COMPANY_LLM_INVALID_RESPONSE";
    public static final String COMPANY_LLM_TIMEOUT =
            "COMPANY_LLM_TIMEOUT";

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
        if (!properties.getAi().isEnabled()
                || properties.getAi().getProvider() != AiProviderType.COMPANY_LLM) {
            return unavailable();
        }

        try {
            AiProviderClient provider = aiProviderClientFactory.getProvider();
            String contextJson = objectMapper.writeValueAsString(context);
            AiGenerationResponse response = provider.generate(new AiGenerationRequest(
                    caseId,
                    "SOURCE_CHANGE_ANALYSIS",
                    systemPrompt(),
                    userPrompt(contextJson),
                    properties.getAi().getCompany().getModel(),
                    properties.getAi().getTemperature(),
                    properties.getAi().getCompany().getMaxOutputChars(),
                    true,
                    Map.of("requestType", "SOURCE_CHANGE_ANALYSIS")
            ));

            if (!response.success()) {
                return failed(response.errorCategory());
            }
            if (response.structuredResponse() == null) {
                return invalidResponse();
            }

            return parse(response.structuredResponse(), response.warnings());
        } catch (Exception ignored) {
            return unavailable();
        }
    }

    private ReasoningResult parse(JsonNode node, List<String> providerWarnings) {
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
                warnings
        );
    }

    private ReasoningResult unavailable() {
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
                List.of(COMPANY_LLM_UNAVAILABLE)
        );
    }

    private ReasoningResult invalidResponse() {
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
                List.of(COMPANY_LLM_INVALID_RESPONSE)
        );
    }

    private ReasoningResult timeout() {
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
                List.of(COMPANY_LLM_TIMEOUT)
        );
    }

    private ReasoningResult failed(String errorCategory) {
        if ("INVALID_JSON".equalsIgnoreCase(errorCategory)) {
            return invalidResponse();
        }
        if ("TIMEOUT".equalsIgnoreCase(errorCategory)) {
            return timeout();
        }
        if (errorCategory != null
                && errorCategory.toUpperCase(java.util.Locale.ROOT)
                .startsWith("HTTP_503")) {
            return unavailable();
        }
        return unavailable();
    }

    private String systemPrompt() {
        return """
                You are ReplayFix source reasoning AI. Return valid JSON only.
                Use only supplied evidence. Separate FACT, INFERENCE and UNKNOWN.
                Do not invent files, methods, commits or logs. Status must remain
                HYPOTHESIS unless supplied replay/test/log evidence confirms.
                """;
    }

    private String userPrompt(String contextJson) {
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
            List<String> warnings
    ) {
    }
}
