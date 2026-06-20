package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;
import com.etiya.replayfix.model.StructuredAiRootCauseAnalysis;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.service.EvidenceSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component("companyLlmProvider")
public class CompanyLlmProviderClient implements AiProviderClient {

    private static final String PROVIDER = "COMPANY_LLM";
    static final String COMPANY_LLM_OUTPUT_TRUNCATED =
            "COMPANY_LLM_OUTPUT_TRUNCATED";
    static final String COMPANY_LLM_EMPTY_RESPONSE =
            "COMPANY_LLM_EMPTY_RESPONSE";

    private final ReplayFixProperties properties;
    private final EvidenceRepository evidenceRepository;
    private final EvidenceSanitizer evidenceSanitizer;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public CompanyLlmProviderClient(
            ReplayFixProperties properties,
            EvidenceRepository evidenceRepository,
            EvidenceSanitizer evidenceSanitizer,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceRepository = evidenceRepository;
        this.evidenceSanitizer = evidenceSanitizer;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConnectivityResult connectivity() {
        ReplayFixProperties.Company cfg = company();
        long startedAt = System.currentTimeMillis();
        boolean baseUrlConfigured = hasText(cfg.getBaseUrl());
        boolean tokenConfigured = hasText(cfg.getToken());
        boolean modelConfigured = hasText(model());

        if (!properties.getAi().isEnabled()) {
            return connectivityResult(
                    false,
                    false,
                    modelConfigured,
                    baseUrlConfigured,
                    tokenConfigured,
                    false,
                    null,
                    startedAt,
                    "AI integration is disabled.",
                    List.of("AI integration is disabled.")
            );
        }

        if (!baseUrlConfigured || !modelConfigured || !tokenConfigured) {
            return connectivityResult(
                    false,
                    true,
                    modelConfigured,
                    baseUrlConfigured,
                    tokenConfigured,
                    false,
                    null,
                    startedAt,
                    "Company LLM configuration is incomplete.",
                    List.of("Company LLM base URL, model and token must be configured.")
            );
        }

        try {
            JsonNode payload = buildChatPayload(
                    "Return strict JSON only: {\"ok\":true}",
                    500
            );
            JsonNode response = post(payload);
            boolean reachable = response != null && !response.isMissingNode();
            return connectivityResult(
                    reachable,
                    true,
                    modelConfigured,
                    baseUrlConfigured,
                    tokenConfigured,
                    reachable,
                    200,
                    startedAt,
                    null,
                    List.of()
            );
        } catch (WebClientResponseException exception) {
            return connectivityResult(
                    false,
                    true,
                    modelConfigured,
                    baseUrlConfigured,
                    tokenConfigured,
                    false,
                    exception.getStatusCode().value(),
                    startedAt,
                    sanitizedError(exception),
                    List.of(sanitizedWarning(exception))
            );
        } catch (Exception exception) {
            return connectivityResult(
                    false,
                    true,
                    modelConfigured,
                    baseUrlConfigured,
                    tokenConfigured,
                    false,
                    null,
                    startedAt,
                    sanitizeError(exception.getMessage()),
                    List.of("Company LLM connectivity failed: " + sanitizeError(exception.getMessage()))
            );
        }
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            if ("SOURCE_CHANGE_ANALYSIS".equalsIgnoreCase(request.requestType())) {
                JsonNode payload = buildSourceReasoningPayload(request);
                JsonNode response = post(payload);
                JsonNode content = parseContent(response);
                List<String> warnings = generationWarnings(content, response);
                return new AiGenerationResponse(
                        true,
                        PROVIDER,
                        model(),
                        requestId(response),
                        finishReason(response),
                        System.currentTimeMillis() - startedAt,
                        payload.toString().length(),
                        content.toString().length(),
                        content,
                        warnings,
                        null,
                        null
                );
            }

            String evidenceBundle = latestAiInputBundle(request.caseId())
                    .orElseThrow(() -> new IllegalStateException(
                            "AI_INPUT_BUNDLE evidence is required for COMPANY_LLM"
                    ));
            JsonNode payload = buildAnalysisPayload(request, evidenceBundle);
            JsonNode response = post(payload);
            JsonNode content = parseContent(response);
            List<String> warnings = generationWarnings(content, response);
            StructuredAiRootCauseAnalysis analysis =
                    toStructuredAnalysis(request, content);
            JsonNode structured = objectMapper.valueToTree(analysis);
            int outputChars = structured.toString().length();

            return new AiGenerationResponse(
                    true,
                    PROVIDER,
                    model(),
                    requestId(response),
                    finishReason(response),
                    System.currentTimeMillis() - startedAt,
                    payload.toString().length(),
                    outputChars,
                    structured,
                    warnings,
                    null,
                    null
            );
        } catch (WebClientResponseException exception) {
            return failedResponse(
                    startedAt,
                    "HTTP_" + exception.getStatusCode().value(),
                    sanitizedWarning(exception)
            );
        } catch (EmptyCompanyLlmResponseException exception) {
            return failedResponse(
                    startedAt,
                    "EMPTY_RESPONSE",
                    "Company LLM response content is empty",
                    exception.warnings()
            );
        } catch (Exception exception) {
            return failedResponse(
                    startedAt,
                    errorCategory(exception),
                    sanitizeError(exception.getMessage())
            );
        }
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean supportsStructuredOutput() {
        return true;
    }

    JsonNode buildAnalysisPayload(
            AiGenerationRequest request,
            String evidenceBundle
    ) {
        String sanitizedBundle = evidenceSanitizer.sanitize(evidenceBundle);
        sanitizedBundle = truncate(sanitizedBundle, company().getMaxInputChars());
        String prompt = companyPrompt(request, sanitizedBundle);
        return buildChatPayload(prompt, company().getMaxOutputChars());
    }

    JsonNode buildSourceReasoningPayload(AiGenerationRequest request) {
        String prompt = evidenceSanitizer.sanitize(firstNonBlank(
                request.userPrompt(),
                ""
        ));
        prompt = truncate(prompt, company().getMaxInputChars());
        return buildChatPayload(prompt, company().getMaxOutputChars());
    }

    private JsonNode buildChatPayload(String prompt, int maxOutputChars) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model());
        payload.put("temperature", properties.getAi().getTemperature());
        payload.put("max_tokens", Math.max(1, maxOutputChars));
        payload.put("messages", List.of(
                Map.of(
                        "role",
                        "system",
                        "content",
                        systemPrompt()
                ),
                Map.of(
                        "role",
                        "user",
                        "content",
                        prompt
                )
        ));
        return objectMapper.valueToTree(payload);
    }

    private JsonNode post(JsonNode payload) {
        ReplayFixProperties.Company cfg = company();
        JsonNode response = webClientBuilder
                .baseUrl(trimTrailingSlash(cfg.getBaseUrl()))
                .build()
                .post()
                .uri(normalizedEndpoint(cfg.getEndpoint()))
                .headers(headers -> applyAuth(headers, cfg))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(1, cfg.getTimeoutMs())));

        if (response == null) {
            throw new IllegalStateException("Company LLM returned an empty response");
        }
        return response;
    }

    private JsonNode parseContent(JsonNode response) {
        JsonNode choice = response
                .path("choices")
                .path(0);
        JsonNode contentNode = choice
                .path("message")
                .path("content");

        if (contentNode.isObject()) {
            return contentNode;
        }

        String content = contentNode.asText("").trim();
        if (content.isBlank()) {
            content = choice.path("text").asText("").trim();
        }
        if (content.isBlank()) {
            content = extractJsonLike(
                    choice
                            .path("message")
                            .path("reasoning_content")
                            .asText("")
            );
        }
        if (content.isBlank()) {
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                String errorMessage = firstNonBlank(
                        error.path("message").asText(null),
                        error.asText(null),
                        error.toString()
                );
                throw new IllegalStateException(
                        "Company LLM response error: " + sanitizeError(errorMessage)
                );
            }
            throw new EmptyCompanyLlmResponseException(
                    "length".equalsIgnoreCase(finishReason(response))
            );
        }

        content = stripJsonFence(content);
        try {
            JsonNode parsed = objectMapper.readTree(content);
            if (parsed.isTextual()) {
                String nestedJson = stripJsonFence(parsed.asText()).trim();
                if (!nestedJson.isBlank()) {
                    return objectMapper.readTree(nestedJson);
                }
            }
            return parsed;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Company LLM response is not valid JSON",
                    exception
            );
        }
    }

    private List<String> generationWarnings(JsonNode content, JsonNode response) {
        List<String> warnings = new ArrayList<>(toStrings(content.path("warnings")));
        if ("length".equalsIgnoreCase(finishReason(response))) {
            warnings.add(COMPANY_LLM_OUTPUT_TRUNCATED);
        }
        return warnings;
    }

    private StructuredAiRootCauseAnalysis toStructuredAnalysis(
            AiGenerationRequest request,
            JsonNode content
    ) {
        double confidence = clamp(content.path("confidence").asDouble(0.0));
        List<String> warnings = toStrings(content.path("warnings"));
        if (!"HYPOTHESIS".equalsIgnoreCase(content.path("status").asText("HYPOTHESIS"))) {
            warnings = new ArrayList<>(warnings);
            warnings.add("Company LLM status normalized to HYPOTHESIS.");
        }

        List<String> recommendedActions =
                toStrings(content.path("recommendedActions"));
        String nextAction = recommendedActions.isEmpty()
                ? "Review supplied ReplayFix evidence before approving next action."
                : recommendedActions.get(0);

        return new StructuredAiRootCauseAnalysis(
                request.caseId(),
                "COMPANY_LLM_ANALYSIS",
                firstNonBlank(
                        content.path("executiveSummary").asText(null),
                        content.path("summary").asText(null),
                        "Company LLM analysis generated from ReplayFix evidence."
                ),
                content.path("probableRootCause").asText("Analysis pending"),
                content.path("impactedComponent").asText("unknown"),
                confidence,
                toStrings(content.path("facts")),
                List.of(),
                toStrings(content.path("inferences")),
                List.of(),
                recommendedActions,
                mergeStrings(
                        content.path("missingEvidence"),
                        content.path("unknowns")
                ),
                nextAction,
                PROVIDER,
                model(),
                false,
                warnings
        );
    }

    private Optional<String> latestAiInputBundle(UUID caseId) {
        return evidenceRepository
                .findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .map(evidence -> firstNonBlank(
                        evidence.getContentText(),
                        evidence.getBody()
                ))
                .filter(this::hasText);
    }

    private String companyPrompt(
            AiGenerationRequest request,
            String evidenceBundle
    ) {
        return """
                Analyze this ReplayFix AI_INPUT_BUNDLE.

                Return valid JSON only:
                {
                  "status": "HYPOTHESIS",
                  "probableRootCause": "...",
                  "confidence": 0.0,
                  "facts": [],
                  "inferences": [],
                  "unknowns": [],
                  "missingEvidence": [],
                  "recommendedActions": [],
                  "warnings": []
                }

                Rules:
                - Use only supplied ReplayFix evidence.
                - Separate FACT / INFERENCE / UNKNOWN.
                - Do not invent logs, traces, files, commits or methods.
                - If evidence is weak, keep status HYPOTHESIS.
                - Include confidence between 0.0 and 1.0.
                - Do not recommend merge or deployment without human approval.
                - Do not include markdown.

                Request metadata:
                %s

                ReplayFix evidence:
                %s
                """.formatted(request.metadata(), evidenceBundle);
    }

    private String systemPrompt() {
        return """
                You are ReplayFix AI. Produce evidence-driven RCA JSON.
                Use only the supplied evidence. Human approval is mandatory for
                any test write, source change, branch, PR, merge or deployment.
                """;
    }

    private void applyAuth(
            HttpHeaders headers,
            ReplayFixProperties.Company cfg
    ) {
        String token = cfg.getToken();
        if (!hasText(token) || "NONE".equalsIgnoreCase(cfg.getAuthType())) {
            return;
        }
        if ("API_KEY".equalsIgnoreCase(cfg.getAuthType())) {
            headers.set("x-api-key", token);
            return;
        }
        headers.setBearerAuth(token);
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage
    ) {
        return failedResponse(startedAt, errorCategory, errorMessage, List.of());
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings
    ) {
        return new AiGenerationResponse(
                false,
                PROVIDER,
                model(),
                null,
                "error",
                System.currentTimeMillis() - startedAt,
                0,
                0,
                null,
                warnings,
                errorCategory,
                errorMessage
        );
    }

    private AiConnectivityResult connectivityResult(
            boolean success,
            boolean enabled,
            boolean modelConfigured,
            boolean baseUrlConfigured,
            boolean tokenConfigured,
            boolean reachable,
            Integer httpStatus,
            long startedAt,
            String sanitizedError,
            List<String> warnings
    ) {
        return new AiConnectivityResult(
                success,
                enabled,
                PROVIDER,
                model(),
                modelConfigured,
                tokenConfigured,
                baseUrlConfigured,
                tokenConfigured,
                reachable,
                httpStatus,
                System.currentTimeMillis() - startedAt,
                sanitizedError,
                warnings
        );
    }

    private ReplayFixProperties.Company company() {
        return properties.getAi().getCompany();
    }

    private String model() {
        return firstNonBlank(
                company().getModel(),
                properties.getAi().getModel(),
                "company-llm"
        );
    }

    private String requestId(JsonNode response) {
        return response.path("id").asText(null);
    }

    private String finishReason(JsonNode response) {
        return response.path("choices").path(0).path("finish_reason")
                .asText("completed");
    }

    private String errorCategory(Exception exception) {
        if (exception instanceof IllegalStateException
                && exception.getMessage() != null
                && exception.getMessage().contains("AI_INPUT_BUNDLE")) {
            return "MISSING_AI_INPUT_BUNDLE";
        }
        if (exception.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return "TIMEOUT";
        }
        if (exception.getMessage() != null
                && exception.getMessage().toLowerCase().contains("timeout")) {
            return "TIMEOUT";
        }
        if (exception instanceof IllegalStateException
                && exception.getMessage() != null
                && exception.getMessage().contains("not valid JSON")) {
            return "INVALID_JSON";
        }
        return "COMPANY_LLM_ERROR";
    }

    private String sanitizedWarning(WebClientResponseException exception) {
        return "Company LLM request failed with HTTP "
                + exception.getStatusCode().value()
                + ": "
                + sanitizeError(exception.getResponseBodyAsString());
    }

    private String sanitizedError(WebClientResponseException exception) {
        return sanitizeError(exception.getMessage());
    }

    private String sanitizeError(String message) {
        String sanitized = evidenceSanitizer.sanitize(firstNonBlank(message, ""));
        return truncate(sanitized, 500);
    }

    private List<String> mergeStrings(JsonNode first, JsonNode second) {
        List<String> values = new ArrayList<>(toStrings(first));
        values.addAll(toStrings(second));
        return values;
    }

    private List<String> toStrings(JsonNode node) {
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

    private String stripJsonFence(String content) {
        return content.trim()
                .replaceFirst("^```json\\s*", "")
                .replaceFirst("^```\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
    }

    private String extractJsonLike(String content) {
        String value = firstNonBlank(content, "").trim();
        if (value.isBlank()) {
            return "";
        }

        String object = extractLastBalanced(value, '{', '}');
        if (!object.isBlank()) {
            return object;
        }
        return extractLastBalanced(value, '[', ']');
    }

    private String extractLastBalanced(
            String value,
            char open,
            char close
    ) {
        int end = value.lastIndexOf(close);
        while (end >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = end; index >= 0; index--) {
                char current = value.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (current == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (current == close) {
                    depth++;
                    continue;
                }
                if (current == open) {
                    depth--;
                    if (depth == 0) {
                        return value.substring(index, end + 1).trim();
                    }
                }
            }
            end = value.lastIndexOf(close, end - 1);
        }
        return "";
    }

    private String normalizedEndpoint(String endpoint) {
        if (!hasText(endpoint)) {
            return "/v1/chat/completions";
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private String trimTrailingSlash(String value) {
        return firstNonBlank(value, "").replaceAll("/+$", "");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static class EmptyCompanyLlmResponseException
            extends RuntimeException {
        private final boolean truncated;

        private EmptyCompanyLlmResponseException(boolean truncated) {
            this.truncated = truncated;
        }

        private List<String> warnings() {
            List<String> warnings = new ArrayList<>();
            warnings.add(COMPANY_LLM_EMPTY_RESPONSE);
            if (truncated) {
                warnings.add(COMPANY_LLM_OUTPUT_TRUNCATED);
            }
            return warnings;
        }
    }
}
