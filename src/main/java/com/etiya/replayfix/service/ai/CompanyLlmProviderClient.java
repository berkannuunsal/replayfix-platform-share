package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
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
    private static final String LITELLM_PROVIDER =
            "LITELLM_OPENAI_COMPATIBLE";
    static final String MODEL_NAME_MUST_USE_OPENAI_PREFIX =
            "MODEL_NAME_MUST_USE_OPENAI_PREFIX";
    static final String MODEL_NOT_ALLOWED = "MODEL_NOT_ALLOWED";
    static final String LLM_USAGE_NOT_RETURNED_BY_PROVIDER =
            "LLM_USAGE_NOT_RETURNED_BY_PROVIDER";
    static final String WEEKLY_BUDGET_USAGE_ESTIMATION_UNAVAILABLE =
            "WEEKLY_BUDGET_USAGE_ESTIMATION_UNAVAILABLE";
    static final String LARGE_PROMPT_COST_RISK = "LARGE_PROMPT_COST_RISK";
    static final String COMPANY_LLM_OUTPUT_TRUNCATED =
            "COMPANY_LLM_OUTPUT_TRUNCATED";
    static final String COMPANY_LLM_EMPTY_RESPONSE =
            "COMPANY_LLM_EMPTY_RESPONSE";
    static final String COMPANY_LLM_JSON_MODE_UNSUPPORTED =
            "COMPANY_LLM_JSON_MODE_UNSUPPORTED";
    static final String PARSE_EMPTY_RESPONSE = "EMPTY_RESPONSE";
    static final String PARSE_NON_JSON_RESPONSE = "NON_JSON_RESPONSE";
    static final String PARSE_JSON_EXTRACTION_FAILED =
            "JSON_EXTRACTION_FAILED";
    static final String PARSE_UNKNOWN = "UNKNOWN";
    private static final long SOURCE_REASONING_TIMEOUT_BUFFER_MS = 5_000L;

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
        return connectivity(null, null);
    }

    @Override
    public AiConnectivityResult connectivity(
            String modelProfile,
            String modelName
    ) {
        ResolvedModel resolved = resolveModel(
                "CODE_ADVISORY",
                modelProfile,
                modelName
        );
        long startedAt = System.currentTimeMillis();
        boolean baseUrlConfigured = hasText(resolved.baseUrl());
        boolean tokenConfigured = hasText(resolved.token());
        boolean modelConfigured = hasText(resolved.modelName());

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
                    List.of("AI integration is disabled."),
                    resolved,
                    null
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
                    List.of("Company LLM base URL, model and token must be configured."),
                    resolved,
                    null
            );
        }

        try {
            JsonNode payload = buildChatPayload(
                    "Return strict JSON only: {\"ok\":true}",
                    500,
                    resolved
            );
            JsonNode response = post(payload, resolved.timeoutMs(), resolved);
            boolean reachable = response != null && !response.isMissingNode();
            Usage usage = usage(response);
            List<String> warnings = usageWarnings(
                    usage,
                    payload.toString().length(),
                    resolved
            );
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
                    warnings,
                    resolved,
                    usage
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
                    List.of(sanitizedWarning(exception)),
                    resolved,
                    null
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
                    List.of("Company LLM connectivity failed: " + sanitizeError(exception.getMessage())),
                    resolved,
                    null
            );
        }
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        long startedAt = System.currentTimeMillis();
        List<String> requestWarnings = new ArrayList<>();
        ResolvedModel resolved = resolveModel(
                profileForRequest(request),
                metadataString(request, "modelProfile"),
                metadataString(request, "modelName")
        );
        try {
            if ("SOURCE_CHANGE_ANALYSIS".equalsIgnoreCase(request.requestType())
                    || "REPLAY_ENVIRONMENT_ADVISORY".equalsIgnoreCase(
                    request.requestType())
                    || "CODE_CHANGE_ADVISORY".equalsIgnoreCase(
                    request.requestType())) {
                JsonNode payload = buildSourceReasoningPayload(
                        request,
                        true,
                        resolved
                );
                long requestTimeoutMs = sourceReasoningTimeoutMs(
                        request,
                        resolved
                );
                JsonNode response;
                try {
                    response = post(payload, requestTimeoutMs, resolved);
                } catch (WebClientResponseException exception) {
                    if (!jsonModeUnsupported(exception)) {
                        throw exception;
                    }
                    requestWarnings.add(COMPANY_LLM_JSON_MODE_UNSUPPORTED);
                    payload = buildSourceReasoningPayload(
                            request,
                            false,
                            resolved
                    );
                    response = post(payload, requestTimeoutMs, resolved);
                }
                JsonNode content = parseContent(response);
                List<String> warnings = generationWarnings(content, response);
                warnings.addAll(requestWarnings);
                Usage usage = usage(response);
                warnings.addAll(usageWarnings(
                        usage,
                        payload.toString().length(),
                        resolved
                ));
                return new AiGenerationResponse(
                        true,
                        resolved.provider(),
                        resolved.modelName(),
                        requestId(response),
                        finishReason(response),
                        System.currentTimeMillis() - startedAt,
                        payload.toString().length(),
                        content.toString().length(),
                        content,
                        warnings,
                        null,
                        null,
                        null,
                        "",
                        resolved.maxOutputTokens(),
                        resolved.profile(),
                        resolved.modelName(),
                        resolved.timeoutSeconds(),
                        resolved.maxPromptChars(),
                        resolved.maxOutputTokens(),
                        resolved.budgetTrackingEnabled(),
                        resolved.budgetPeriod(),
                        resolved.weeklyBudgetUsd(),
                        usage.available(),
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.totalTokens(),
                        responseShape(response, "content")
                );
            }

            String evidenceBundle = latestAiInputBundle(request.caseId())
                    .orElseThrow(() -> new IllegalStateException(
                            "AI_INPUT_BUNDLE evidence is required for COMPANY_LLM"
                    ));
            JsonNode payload = buildAnalysisPayload(request, evidenceBundle, resolved);
            JsonNode response = post(payload, resolved.timeoutMs(), resolved);
            JsonNode content = parseContent(response);
            List<String> warnings = generationWarnings(content, response);
            Usage usage = usage(response);
            warnings.addAll(usageWarnings(
                    usage,
                    payload.toString().length(),
                    resolved
            ));
            StructuredAiRootCauseAnalysis analysis =
                    toStructuredAnalysis(request, content);
            JsonNode structured = objectMapper.valueToTree(analysis);
            int outputChars = structured.toString().length();

            return new AiGenerationResponse(
                    true,
                    resolved.provider(),
                    resolved.modelName(),
                    requestId(response),
                    finishReason(response),
                    System.currentTimeMillis() - startedAt,
                    payload.toString().length(),
                    outputChars,
                    structured,
                    warnings,
                    null,
                    null,
                    null,
                    "",
                    resolved.maxOutputTokens(),
                    resolved.profile(),
                    resolved.modelName(),
                    resolved.timeoutSeconds(),
                    resolved.maxPromptChars(),
                    resolved.maxOutputTokens(),
                    resolved.budgetTrackingEnabled(),
                    resolved.budgetPeriod(),
                    resolved.weeklyBudgetUsd(),
                    usage.available(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.totalTokens(),
                    responseShape(response, "content")
            );
        } catch (WebClientResponseException exception) {
            return failedResponse(
                    startedAt,
                    "HTTP_" + exception.getStatusCode().value(),
                    sanitizedWarning(exception),
                    resolved
            );
        } catch (EmptyCompanyLlmResponseException exception) {
            return failedResponse(
                    startedAt,
                    "EMPTY_RESPONSE",
                    "Company LLM response content is empty",
                    mergeWarnings(requestWarnings, exception.warnings()),
                    PARSE_EMPTY_RESPONSE,
                    "",
                    request.maxOutputChars(),
                    exception.responseShape(),
                    resolved
            );
        } catch (CompanyLlmParseException exception) {
            return failedResponse(
                    startedAt,
                    "INVALID_JSON",
                    sanitizeError(exception.getMessage()),
                    requestWarnings,
                    exception.parseErrorCategory(),
                    exception.outputPreview(),
                    request.maxOutputChars(),
                    exception.responseShape(),
                    resolved
            );
        } catch (Exception exception) {
            return failedResponse(
                    startedAt,
                    errorCategory(exception),
                    sanitizeError(exception.getMessage()),
                    requestWarnings,
                    resolved
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
        return buildAnalysisPayload(
                request,
                evidenceBundle,
                resolveModel(profileForRequest(request), "", "")
        );
    }

    private JsonNode buildAnalysisPayload(
            AiGenerationRequest request,
            String evidenceBundle,
            ResolvedModel resolved
    ) {
        String sanitizedBundle = evidenceSanitizer.sanitize(evidenceBundle);
        sanitizedBundle = truncate(
                sanitizedBundle,
                Math.max(1, resolved.maxPromptChars())
        );
        String prompt = companyPrompt(request, sanitizedBundle);
        return buildChatPayload(
                prompt,
                resolved.maxOutputTokens(),
                resolved
        );
    }

    JsonNode buildSourceReasoningPayload(AiGenerationRequest request) {
        return buildSourceReasoningPayload(
                request,
                true,
                resolveModel(
                        profileForRequest(request),
                        metadataString(request, "modelProfile"),
                        metadataString(request, "modelName")
                )
        );
    }

    JsonNode buildSourceReasoningPayload(
            AiGenerationRequest request,
            boolean jsonMode
    ) {
        return buildSourceReasoningPayload(
                request,
                jsonMode,
                resolveModel(
                        profileForRequest(request),
                        metadataString(request, "modelProfile"),
                        metadataString(request, "modelName")
                )
        );
    }

    private JsonNode buildSourceReasoningPayload(
            AiGenerationRequest request,
            boolean jsonMode,
            ResolvedModel resolved
    ) {
        String prompt = evidenceSanitizer.sanitize(firstNonBlank(
                request.userPrompt(),
                ""
        ));
        prompt = truncate(prompt, Math.max(1, resolved.maxPromptChars()));
        return buildChatPayload(
                firstNonBlank(request.systemPrompt(), systemPrompt()),
                prompt,
                Math.max(1, Math.min(
                        request.maxOutputChars(),
                        resolved.maxOutputTokens()
                )),
                jsonMode,
                resolved
        );
    }

    private JsonNode buildChatPayload(String prompt, int maxOutputChars) {
        return buildChatPayload(
                systemPrompt(),
                prompt,
                maxOutputChars,
                false,
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private JsonNode buildChatPayload(
            String systemPrompt,
            String prompt,
            int maxOutputChars
    ) {
        return buildChatPayload(
                systemPrompt,
                prompt,
                maxOutputChars,
                false,
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private JsonNode buildChatPayload(
            String prompt,
            int maxOutputChars,
            ResolvedModel resolved
    ) {
        return buildChatPayload(systemPrompt(), prompt, maxOutputChars, false, resolved);
    }

    private JsonNode buildChatPayload(
            String systemPrompt,
            String prompt,
            int maxOutputChars,
            boolean jsonMode
    ) {
        return buildChatPayload(
                systemPrompt,
                prompt,
                maxOutputChars,
                jsonMode,
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private JsonNode buildChatPayload(
            String systemPrompt,
            String prompt,
            int maxOutputChars,
            boolean jsonMode,
            ResolvedModel resolved
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", resolved.modelName());
        payload.put("temperature", properties.getAi().getTemperature());
        payload.put("max_tokens", Math.max(1, maxOutputChars));
        payload.put("messages", List.of(
                Map.of(
                        "role",
                        "system",
                        "content",
                        systemPrompt
                ),
                Map.of(
                        "role",
                        "user",
                        "content",
                        prompt
                )
        ));
        if (jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }
        return objectMapper.valueToTree(payload);
    }

    private JsonNode post(JsonNode payload) {
        ResolvedModel resolved = resolveModel("CODE_ADVISORY", "", "");
        return post(payload, Math.max(1, resolved.timeoutMs()), resolved);
    }

    private JsonNode post(JsonNode payload, long timeoutMs) {
        return post(payload, timeoutMs, resolveModel("CODE_ADVISORY", "", ""));
    }

    private JsonNode post(
            JsonNode payload,
            long timeoutMs,
            ResolvedModel resolved
    ) {
        JsonNode response = webClientBuilder
                .baseUrl(trimTrailingSlash(resolved.baseUrl()))
                .build()
                .post()
                .uri(normalizedEndpoint(resolved.endpoint()))
                .headers(headers -> applyAuth(headers, resolved))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(1, timeoutMs)));

        if (response == null) {
            throw new IllegalStateException("Company LLM returned an empty response");
        }
        return response;
    }

    long sourceReasoningTimeoutMs(AiGenerationRequest request) {
        return sourceReasoningTimeoutMs(
                request,
                resolveModel(
                        profileForRequest(request),
                        metadataString(request, "modelProfile"),
                        metadataString(request, "modelName")
                )
        );
    }

    long sourceReasoningTimeoutMs(
            AiGenerationRequest request,
            ResolvedModel resolved
    ) {
        long requestedSeconds = metadataLong(
                request,
                "companyLlmTimeoutSeconds"
        );
        long requestedMs = requestedSeconds > 0
                ? requestedSeconds * 1_000L + SOURCE_REASONING_TIMEOUT_BUFFER_MS
                : 0L;
        return Math.max(Math.max(1, resolved.timeoutMs()), requestedMs);
    }

    private JsonNode parseContent(JsonNode response) {
        JsonNode choice = response
                .path("choices")
                .path(0);
        JsonNode contentNode = choice
                .path("message")
                .path("content");
        Map<String, Object> shape = responseShape(response, "");

        if (contentNode.isObject()) {
            return contentNode;
        }

        String content = contentNode.asText("").trim();
        String contentSource = content.isBlank() ? "" : "content";
        if (content.isBlank()) {
            content = choice.path("text").asText("").trim();
            if (!content.isBlank()) {
                contentSource = "text";
            }
        }
        if (content.isBlank()) {
            content = extractJsonLike(
                    choice
                            .path("message")
                            .path("reasoning_content")
                            .asText("")
            );
            if (!content.isBlank()) {
                contentSource = "reasoning_content";
            }
        }
        shape = responseShape(response, contentSource);
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
                    "length".equalsIgnoreCase(finishReason(response)),
                    shape
            );
        }

        try {
            return parseJsonContent(content);
        } catch (Exception exception) {
            throw new CompanyLlmParseException(
                    "Company LLM response is not valid JSON",
                    "reasoning_content".equals(contentSource)
                            ? PARSE_JSON_EXTRACTION_FAILED
                            : PARSE_NON_JSON_RESPONSE,
                    "reasoning_content".equals(contentSource)
                            ? ""
                            : safeOutputPreview(content),
                    shape,
                    exception
            );
        }
    }

    private JsonNode parseJsonContent(String content) throws Exception {
        String value = stripJsonFence(firstNonBlank(content, ""));
        try {
            return parseJsonNode(value);
        } catch (Exception ignored) {
            String extracted = extractFirstJsonObject(value);
            if (!extracted.isBlank()) {
                return parseJsonNode(extracted);
            }
            throw ignored;
        }
    }

    private JsonNode parseJsonNode(String content) throws Exception {
        JsonNode parsed = objectMapper.readTree(content);
        if (parsed.isTextual()) {
            String nestedJson = stripJsonFence(parsed.asText()).trim();
            if (!nestedJson.isBlank()) {
                try {
                    return parseJsonNode(nestedJson);
                } catch (Exception ignored) {
                    String extracted = extractFirstJsonObject(nestedJson);
                    if (!extracted.isBlank()) {
                        return parseJsonNode(extracted);
                    }
                    throw ignored;
                }
            }
        }
        return parsed;
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
                ? "Review supplied ReplayLab evidence before approving next action."
                : recommendedActions.get(0);

        return new StructuredAiRootCauseAnalysis(
                request.caseId(),
                "COMPANY_LLM_ANALYSIS",
                firstNonBlank(
                        content.path("executiveSummary").asText(null),
                        content.path("summary").asText(null),
                        "Company LLM analysis generated from ReplayLab evidence."
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
                Analyze this ReplayLab AI_INPUT_BUNDLE.

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
                - Use only supplied ReplayLab evidence.
                - Separate FACT / INFERENCE / UNKNOWN.
                - Do not invent logs, traces, files, commits or methods.
                - If evidence is weak, keep status HYPOTHESIS.
                - Include confidence between 0.0 and 1.0.
                - Do not recommend merge or deployment without human approval.
                - Do not include markdown.

                Request metadata:
                %s

                ReplayLab evidence:
                %s
                """.formatted(request.metadata(), evidenceBundle);
    }

    private String systemPrompt() {
        return """
                You are ReplayLab AI. Produce evidence-driven RCA JSON.
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

    private void applyAuth(HttpHeaders headers, ResolvedModel resolved) {
        String token = resolved.token();
        if (!hasText(token) || "NONE".equalsIgnoreCase(resolved.authType())) {
            return;
        }
        if ("API_KEY".equalsIgnoreCase(resolved.authType())) {
            headers.set("x-api-key", token);
            return;
        }
        headers.setBearerAuth(token);
    }

    private ResolvedModel resolveModel(
            String defaultProfile,
            String requestedProfile,
            String requestedModelName
    ) {
        boolean liteLlm = isLiteLlm(requestedProfile, requestedModelName);
        ReplayFixProperties.Llm llm = properties.getLlm();
        ReplayFixProperties.Company company = company();
        String profile = liteLlm
                ? firstNonBlank(requestedProfile, defaultProfile)
                : "";
        ReplayFixProperties.LlmModelProfile profileConfig =
                liteLlm ? llm.getModelProfiles().get(profile) : null;
        String configuredModel = profileConfig == null
                ? ""
                : profileConfig.getModelName();
        String modelName = firstNonBlank(
                requestedModelName,
                configuredModel,
                liteLlm ? llm.getDefaultModelName() : company.getModel(),
                properties.getAi().getModel(),
                liteLlm ? "openai/gpt-3.5-turbo" : "company-llm"
        );
        if (liteLlm) {
            validateLiteLlmModel(modelName);
        }
        int timeoutSeconds = profileConfig == null
                ? Math.max(1, (int) (company.getTimeoutMs() / 1000L))
                : Math.max(1, profileConfig.getTimeoutSeconds());
        int maxPromptChars = profileConfig == null
                ? Math.max(1, company.getMaxInputChars())
                : Math.max(1, profileConfig.getMaxPromptChars());
        int maxOutputTokens = profileConfig == null
                ? Math.max(1, company.getMaxOutputChars())
                : Math.max(1, profileConfig.getMaxOutputTokens());
        String baseUrl = liteLlm
                ? firstNonBlank(llm.getBaseUrl(), company.getBaseUrl())
                : company.getBaseUrl();
        String token = liteLlm
                ? firstNonBlank(env(llm.getApiKeyEnv()), company.getToken())
                : company.getToken();
        return new ResolvedModel(
                liteLlm ? LITELLM_PROVIDER : PROVIDER,
                profile,
                modelName,
                timeoutSeconds,
                timeoutSeconds * 1000L,
                maxPromptChars,
                maxOutputTokens,
                baseUrl,
                liteLlm ? "/chat/completions" : company.getEndpoint(),
                token,
                company.getAuthType(),
                liteLlm && llm.isBudgetTrackingEnabled(),
                liteLlm ? firstNonBlank(llm.getBudgetPeriod(), "WEEKLY") : "WEEKLY",
                liteLlm ? llm.getWeeklyBudgetUsd() : 200.0
        );
    }

    private void validateLiteLlmModel(String modelName) {
        if (!modelName.startsWith("openai/")) {
            throw new IllegalArgumentException(
                    MODEL_NAME_MUST_USE_OPENAI_PREFIX
            );
        }
        List<String> allowed = properties.getLlm().getAllowedModelNames();
        if (allowed != null && !allowed.isEmpty()
                && !allowed.contains(modelName)) {
            throw new IllegalArgumentException(MODEL_NOT_ALLOWED);
        }
    }

    private boolean isLiteLlm() {
        return isLiteLlm("", "");
    }

    private boolean isLiteLlm(
            String requestedProfile,
            String requestedModelName
    ) {
        ReplayFixProperties.Llm llm = properties.getLlm();
        return properties.getAi().getProvider()
                == AiProviderType.LITELLM_OPENAI_COMPATIBLE
                || llm.getProvider() == AiProviderType.LITELLM_OPENAI_COMPATIBLE
                || (hasText(requestedProfile)
                && !llm.isAllowPlainModelNames())
                || (hasText(requestedModelName)
                && !llm.isAllowPlainModelNames())
                || (hasText(llm.getBaseUrl())
                && firstNonBlank(llm.getDefaultModelName(), "")
                .startsWith("openai/"));
    }

    private String profileForRequest(AiGenerationRequest request) {
        String explicit = metadataString(request, "modelProfile");
        if (hasText(explicit)) {
            return explicit;
        }
        String mode = metadataString(request, "advisoryMode");
        if ("TEST_SUGGESTION".equalsIgnoreCase(mode)) {
            return "TEST_SUGGESTION";
        }
        if ("RISK_REVIEW".equalsIgnoreCase(mode)) {
            return "RISK_REVIEW";
        }
        return "CODE_ADVISORY";
    }

    private Usage usage(JsonNode response) {
        JsonNode usage = response == null ? null : response.path("usage");
        if (usage == null || usage.isMissingNode() || !usage.isObject()) {
            return Usage.unavailable();
        }
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(prompt + completion);
        return new Usage(true, prompt, completion, total);
    }

    private List<String> usageWarnings(
            Usage usage,
            int promptChars,
            ResolvedModel resolved
    ) {
        List<String> warnings = new ArrayList<>();
        if (!usage.available()) {
            warnings.add(LLM_USAGE_NOT_RETURNED_BY_PROVIDER);
            if (resolved != null && resolved.budgetTrackingEnabled()) {
                warnings.add(WEEKLY_BUDGET_USAGE_ESTIMATION_UNAVAILABLE);
            }
        }
        if (promptChars > 10_000) {
            warnings.add(LARGE_PROMPT_COST_RISK);
        }
        return warnings;
    }

    private String env(String name) {
        if (!hasText(name)) {
            return "";
        }
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                List.of(),
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            ResolvedModel resolved
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                List.of(),
                resolved
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                warnings,
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings,
            ResolvedModel resolved
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                warnings,
                parseErrorCategoryFrom(errorCategory),
                "",
                0,
                Map.of(),
                resolved
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                warnings,
                parseErrorCategory,
                outputPreview,
                effectiveOutputTokenLimit,
                Map.of(),
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit,
            ResolvedModel resolved
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                warnings,
                parseErrorCategory,
                outputPreview,
                effectiveOutputTokenLimit,
                Map.of(),
                resolved
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit,
            Map<String, Object> responseShape
    ) {
        return failedResponse(
                startedAt,
                errorCategory,
                errorMessage,
                warnings,
                parseErrorCategory,
                outputPreview,
                effectiveOutputTokenLimit,
                responseShape,
                resolveModel("CODE_ADVISORY", "", "")
        );
    }

    private AiGenerationResponse failedResponse(
            long startedAt,
            String errorCategory,
            String errorMessage,
            List<String> warnings,
            String parseErrorCategory,
            String outputPreview,
            int effectiveOutputTokenLimit,
            Map<String, Object> responseShape,
            ResolvedModel resolved
    ) {
        Map<String, Object> safeShape = new LinkedHashMap<>(
                responseShape == null ? Map.of() : responseShape
        );
        if (parseErrorCategory != null && !parseErrorCategory.isBlank()) {
            safeShape.put("parseErrorCategory", parseErrorCategory);
        }
        return new AiGenerationResponse(
                false,
                resolved.provider(),
                resolved.modelName(),
                null,
                "error",
                System.currentTimeMillis() - startedAt,
                0,
                0,
                null,
                warnings,
                errorCategory,
                errorMessage,
                parseErrorCategory,
                safeOutputPreview(outputPreview),
                Math.max(0, effectiveOutputTokenLimit),
                resolved.profile(),
                resolved.modelName(),
                resolved.timeoutSeconds(),
                resolved.maxPromptChars(),
                resolved.maxOutputTokens(),
                resolved.budgetTrackingEnabled(),
                resolved.budgetPeriod(),
                resolved.weeklyBudgetUsd(),
                false,
                0,
                0,
                0,
                safeShape
        );
    }

    private String parseErrorCategoryFrom(String errorCategory) {
        if ("EMPTY_RESPONSE".equalsIgnoreCase(errorCategory)) {
            return PARSE_EMPTY_RESPONSE;
        }
        if ("INVALID_JSON".equalsIgnoreCase(errorCategory)) {
            return PARSE_NON_JSON_RESPONSE;
        }
        return null;
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
            List<String> warnings,
            ResolvedModel resolved,
            Usage usage
    ) {
        Usage safeUsage = usage == null ? Usage.unavailable() : usage;
        return new AiConnectivityResult(
                success,
                enabled,
                resolved.provider(),
                resolved.modelName(),
                modelConfigured,
                tokenConfigured,
                baseUrlConfigured,
                tokenConfigured,
                reachable,
                httpStatus,
                System.currentTimeMillis() - startedAt,
                sanitizedError,
                resolved.profile(),
                resolved.modelName(),
                resolved.timeoutSeconds(),
                resolved.maxPromptChars(),
                resolved.maxOutputTokens(),
                resolved.budgetTrackingEnabled(),
                resolved.budgetPeriod(),
                resolved.weeklyBudgetUsd(),
                safeUsage.available(),
                safeUsage.promptTokens(),
                safeUsage.completionTokens(),
                safeUsage.totalTokens(),
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

    private String safeOutputPreview(String output) {
        String sanitized = evidenceSanitizer.sanitize(firstNonBlank(output, ""));
        sanitized = sanitized.replaceAll("(?im)^\\s*at\\s+[\\w.$]+\\([^\\r\\n]*\\)\\s*$", "");
        sanitized = sanitized.replaceAll("(?is)reasoning_content\\s*[:=].*", "[REDACTED_REASONING_CONTENT]");
        return truncate(sanitized.trim(), 500);
    }

    private boolean jsonModeUnsupported(WebClientResponseException exception) {
        if (exception.getStatusCode().value() != 400) {
            return false;
        }
        String message = firstNonBlank(exception.getResponseBodyAsString(), "")
                .toLowerCase(java.util.Locale.ROOT);
        return message.contains("response_format")
                || message.contains("json mode")
                || message.contains("json_object")
                || message.contains("unsupported");
    }

    private long metadataLong(
            AiGenerationRequest request,
            String key
    ) {
        if (request == null || request.metadata() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(firstNonBlank(request.metadata().get(key), ""));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String metadataString(AiGenerationRequest request, String key) {
        if (request == null || request.metadata() == null) {
            return "";
        }
        return firstNonBlank(request.metadata().get(key), "");
    }

    private List<String> mergeWarnings(
            List<String> first,
            List<String> second
    ) {
        List<String> values = new ArrayList<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return values;
    }

    private Map<String, Object> responseShape(
            JsonNode response,
            String extractionSource
    ) {
        JsonNode choice = response.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode content = message.path("content");
        JsonNode text = choice.path("text");
        JsonNode reasoning = message.path("reasoning_content");
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("finishReason", finishReason(response));
        shape.put("messageKeys", fieldNames(message));
        shape.put("hasContent", hasNodeText(content));
        shape.put("contentLength", nodeTextLength(content));
        shape.put("hasText", hasNodeText(text));
        shape.put("textLength", nodeTextLength(text));
        shape.put("hasReasoningContent", hasNodeText(reasoning));
        shape.put("reasoningContentLength", nodeTextLength(reasoning));
        shape.put("extractionSource", firstNonBlank(extractionSource, ""));
        shape.put("parseErrorCategory", "");
        return shape;
    }

    private List<String> fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private boolean hasNodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        return !node.asText("").isBlank();
    }

    private int nodeTextLength(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        return node.asText("").length();
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

        String object = extractFirstJsonObject(value);
        if (!object.isBlank()) {
            return object;
        }
        return extractLastBalanced(value, '[', ']');
    }

    private String extractFirstJsonObject(String content) {
        return extractFirstBalanced(firstNonBlank(content, ""), '{', '}');
    }

    private String extractFirstBalanced(
            String value,
            char open,
            char close
    ) {
        for (int start = value.indexOf(open);
             start >= 0;
             start = value.indexOf(open, start + 1)) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < value.length(); index++) {
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
                if (current == open) {
                    depth++;
                    continue;
                }
                if (current == close) {
                    depth--;
                    if (depth == 0) {
                        return value.substring(start, index + 1).trim();
                    }
                }
            }
        }
        return "";
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

    private record ResolvedModel(
            String provider,
            String profile,
            String modelName,
            int timeoutSeconds,
            long timeoutMs,
            int maxPromptChars,
            int maxOutputTokens,
            String baseUrl,
            String endpoint,
            String token,
            String authType,
            boolean budgetTrackingEnabled,
            String budgetPeriod,
            double weeklyBudgetUsd
    ) {
    }

    private record Usage(
            boolean available,
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {
        private static Usage unavailable() {
            return new Usage(false, 0, 0, 0);
        }
    }

    private static class EmptyCompanyLlmResponseException
            extends RuntimeException {
        private final boolean truncated;
        private final Map<String, Object> responseShape;

        private EmptyCompanyLlmResponseException(
                boolean truncated,
                Map<String, Object> responseShape
        ) {
            this.truncated = truncated;
            this.responseShape = responseShape == null ? Map.of() : responseShape;
        }

        private List<String> warnings() {
            List<String> warnings = new ArrayList<>();
            warnings.add(COMPANY_LLM_EMPTY_RESPONSE);
            if (truncated) {
                warnings.add(COMPANY_LLM_OUTPUT_TRUNCATED);
            }
            return warnings;
        }

        private Map<String, Object> responseShape() {
            return responseShape;
        }
    }

    private static class CompanyLlmParseException extends RuntimeException {
        private final String parseErrorCategory;
        private final String outputPreview;
        private final Map<String, Object> responseShape;

        private CompanyLlmParseException(
                String message,
                String parseErrorCategory,
                String outputPreview,
                Map<String, Object> responseShape,
                Throwable cause
        ) {
            super(message, cause);
            this.parseErrorCategory = parseErrorCategory == null
                    ? PARSE_UNKNOWN
                    : parseErrorCategory;
            this.outputPreview = outputPreview == null ? "" : outputPreview;
            Map<String, Object> shape = new LinkedHashMap<>(
                    responseShape == null ? Map.of() : responseShape
            );
            shape.put("parseErrorCategory", this.parseErrorCategory);
            this.responseShape = Map.copyOf(shape);
        }

        private String parseErrorCategory() {
            return parseErrorCategory;
        }

        private String outputPreview() {
            return outputPreview;
        }

        private Map<String, Object> responseShape() {
            return responseShape;
        }
    }
}
