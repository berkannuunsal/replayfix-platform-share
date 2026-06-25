package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.AiProviderType;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.service.EvidenceSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyLlmProviderClientTest {

    private static final String SECRET = "secret-company-token";

    private ReplayLabProperties properties;
    private EvidenceRepository evidenceRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new ReplayLabProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getAi().setModel("fallback-model");
        properties.getAi().getCompany().setBaseUrl("https://llm.example.test/v1");
        properties.getAi().getCompany().setEndpoint("/chat/completions");
        properties.getAi().getCompany().setModel("company-model");
        properties.getAi().getCompany().setToken(SECRET);
        properties.getAi().getCompany().setTimeoutMs(1000);
        properties.getAi().getCompany().setMaxInputChars(10000);
        properties.getAi().getCompany().setMaxOutputChars(2000);

        evidenceRepository = mock(EvidenceRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void shouldBuildSanitizedPayload() {
        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationRequest request = request(UUID.randomUUID());

        JsonNode payload = client.buildAnalysisPayload(
                request,
                "{\"Authorization\":\"Bearer raw-token\",\"password\":\"abc\",\"safe\":\"value\"}"
        );

        String payloadText = payload.toString();
        assertThat(payloadText).doesNotContain("raw-token");
        assertThat(payloadText).doesNotContain("password\":\"abc");
        assertThat(payloadText).contains("[REDACTED]");
        assertThat(payloadText).contains("Use only supplied ReplayLab evidence");
    }

    @Test
    void sourceChangeAnalysisPayloadUsesRequestMaxOutputTokens() {
        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationRequest request = new AiGenerationRequest(
                UUID.randomUUID(),
                "SOURCE_CHANGE_ANALYSIS",
                "Return only compact JSON.",
                "{\"contextMode\":\"MINIMAL\"}",
                "company-model",
                0.1,
                500,
                true,
                Map.of("requestType", "SOURCE_CHANGE_ANALYSIS")
        );

        JsonNode payload = client.buildSourceReasoningPayload(request);

        assertThat(payload.path("max_tokens").asInt()).isEqualTo(500);
        assertThat(payload.path("response_format").path("type").asText())
                .isEqualTo("json_object");
        assertThat(payload.path("messages").path(0).path("content").asText())
                .isEqualTo("Return only compact JSON.");
        assertThat(payload.path("messages").path(1).path("content").asText())
                .contains("\"contextMode\":\"MINIMAL\"");
    }

    @Test
    void liteLlmDefaultModelUsesOpenAiPrefix() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        JsonNode payload = client.buildSourceReasoningPayload(
                sourceRequest(UUID.randomUUID())
        );

        assertThat(payload.path("model").asText())
                .isEqualTo("openai/gpt-4o-mini");
    }

    @Test
    void backendMethodMapsToCodeAdvisoryProfile() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        JsonNode payload = client.buildSourceReasoningPayload(
                sourceRequestWithMetadata(Map.of(
                        "requestType", "CODE_CHANGE_ADVISORY",
                        "advisoryMode", "BACKEND_METHOD"
                ))
        );

        assertThat(payload.path("model").asText())
                .isEqualTo("openai/gpt-4o-mini");
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(500);
    }

    @Test
    void testSuggestionMapsToTestSuggestionProfile() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        JsonNode payload = client.buildSourceReasoningPayload(
                sourceRequestWithMetadata(Map.of(
                        "requestType", "CODE_CHANGE_ADVISORY",
                        "advisoryMode", "TEST_SUGGESTION"
                ))
        );

        assertThat(payload.path("model").asText())
                .isEqualTo("openai/gpt-4o-mini");
    }

    @Test
    void riskReviewMapsToRiskReviewProfile() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        JsonNode payload = client.buildSourceReasoningPayload(
                sourceRequestWithMetadata(Map.of(
                        "requestType", "CODE_CHANGE_ADVISORY",
                        "advisoryMode", "RISK_REVIEW"
                ))
        );

        assertThat(payload.path("model").asText())
                .isEqualTo("openai/gpt-4o");
    }

    @Test
    void explicitAllowedLiteLlmModelIsAccepted() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        JsonNode payload = client.buildSourceReasoningPayload(
                sourceRequestWithMetadata(Map.of(
                        "requestType", "CODE_CHANGE_ADVISORY",
                        "modelName", "openai/gpt-3.5-turbo"
                ))
        );

        assertThat(payload.path("model").asText())
                .isEqualTo("openai/gpt-3.5-turbo");
    }

    @Test
    void plainLiteLlmModelNameIsRejected() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.buildSourceReasoningPayload(sourceRequestWithMetadata(
                        Map.of("modelName", "gpt-3.5-turbo")
                )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NAME_MUST_USE_OPENAI_PREFIX");
    }

    @Test
    void unknownLiteLlmModelNameIsRejected() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(okResponse());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.buildSourceReasoningPayload(sourceRequestWithMetadata(
                        Map.of("modelName", "openai/not-allowed")
                )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NOT_ALLOWED");
    }

    @Test
    void liteLlmResponseUsageTokensAreCaptured() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(chatResponseWithUsage(
                "stop",
                sourceJson("usage captured"),
                123,
                456,
                579
        ));

        AiGenerationResponse response = client.generate(
                sourceRequestWithMetadata(Map.of(
                        "requestType", "CODE_CHANGE_ADVISORY",
                        "advisoryMode", "BACKEND_METHOD"
                ))
        );

        assertThat(response.provider())
                .isEqualTo("LITELLM_OPENAI_COMPATIBLE");
        assertThat(response.effectiveModelName())
                .isEqualTo("openai/gpt-4o-mini");
        assertThat(response.modelProfile()).isEqualTo("CODE_ADVISORY");
        assertThat(response.budgetTrackingEnabled()).isTrue();
        assertThat(response.budgetPeriod()).isEqualTo("WEEKLY");
        assertThat(response.weeklyBudgetUsd()).isEqualTo(200.0);
        assertThat(response.estimatedUsageAvailable()).isTrue();
        assertThat(response.promptTokenCount()).isEqualTo(123);
        assertThat(response.completionTokenCount()).isEqualTo(456);
        assertThat(response.totalTokenCount()).isEqualTo(579);
        assertThat(response.toString()).doesNotContain(SECRET);
    }

    @Test
    void liteLlmMissingUsageReturnsWarning() {
        configureLiteLlm();
        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                sourceJson("missing usage"),
                null,
                null
        ));

        AiGenerationResponse response = client.generate(
                sourceRequestWithMetadata(Map.of(
                        "requestType", "CODE_CHANGE_ADVISORY"
                ))
        );

        assertThat(response.estimatedUsageAvailable()).isFalse();
        assertThat(response.warnings())
                .contains(
                        "LLM_USAGE_NOT_RETURNED_BY_PROVIDER",
                        "WEEKLY_BUDGET_USAGE_ESTIMATION_UNAVAILABLE"
                );
    }

    @Test
    void oldMonthlyBudgetConfigFallsBackToWeeklyBudgetValue() {
        ReplayLabProperties.Llm llm = new ReplayLabProperties.Llm();
        llm.setMonthlyBudgetUsd(350.0);

        assertThat(llm.getBudgetPeriod()).isEqualTo("WEEKLY");
        assertThat(llm.getWeeklyBudgetUsd()).isEqualTo(350.0);
    }

    @Test
    void sourceChangeAnalysisPayloadCanDisableJsonMode() {
        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationRequest request = sourceRequest(UUID.randomUUID());

        JsonNode payload = client.buildSourceReasoningPayload(request, false);

        assertThat(payload.has("response_format")).isFalse();
    }

    @Test
    void sourceReasoningTimeoutUsesRequestedCompanyLlmTimeout() {
        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationRequest request = new AiGenerationRequest(
                UUID.randomUUID(),
                "SOURCE_CHANGE_ANALYSIS",
                "Return only compact JSON.",
                "{\"contextMode\":\"MINIMAL\"}",
                "company-model",
                0.1,
                3_000,
                true,
                Map.of(
                        "requestType", "SOURCE_CHANGE_ANALYSIS",
                        "companyLlmTimeoutSeconds", "45"
                )
        );

        long timeoutMs = client.sourceReasoningTimeoutMs(request);

        assertThat(timeoutMs).isEqualTo(50_000L);
        assertThat(timeoutMs).isGreaterThan(30_000L);
    }

    @Test
    void unsupportedJsonModeRetriesWithoutResponseFormat() {
        UUID caseId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    if (attempts.incrementAndGet() == 1) {
                        return Mono.just(ClientResponse
                                .create(HttpStatus.BAD_REQUEST)
                                .header("Content-Type", "application/json")
                                .body("{\"error\":{\"message\":\"response_format unsupported\"}}")
                                .build());
                    }
                    return Mono.just(ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(chatResponse(
                                    "stop",
                                    sourceJson("retry success"),
                                    null,
                                    null
                            ))
                            .build());
                });
        CompanyLlmProviderClient client = new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );

        AiGenerationResponse response = client.generate(sourceRequest(caseId));

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(response.success()).isTrue();
        assertThat(response.warnings())
                .contains(CompanyLlmProviderClient
                        .COMPANY_LLM_JSON_MODE_UNSUPPORTED);
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("retry success");
    }

    @Test
    void shouldParseSuccessfulResponse() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{\"jiraKey\":\"FIZZMS-10228\"}")));

        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("COMPANY_LLM");
        assertThat(response.model()).isEqualTo("company-model");
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("API validation mismatch");
        assertThat(response.structuredResponse().path("confidence").asDouble())
                .isEqualTo(0.42);
    }

    @Test
    void parsesChoicesMessageContent() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                sourceJson("content response"),
                sourceJson("reasoning response"),
                sourceJson("text response")
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("content response");
    }

    @Test
    void trimsLeadingAndTrailingWhitespaceAroundContent() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "\n  " + sourceJson("trimmed content") + "  \n",
                null,
                null
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("trimmed content");
    }

    @Test
    void parsesJsonContentString() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                quoted(sourceJson("json string content")),
                null,
                null
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("json string content");
    }

    @Test
    void parsesFencedJsonContent() {
        UUID caseId = UUID.randomUUID();
        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "```json\n" + sourceJson("fenced content") + "\n```",
                null,
                null
        ));

        AiGenerationResponse response = client.generate(sourceRequest(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("fenced content");
    }

    @Test
    void parsesJsonWithTextBeforeAndAfterContent() {
        UUID caseId = UUID.randomUUID();
        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "final answer:\n" + sourceJson("embedded content")
                        + "\nthank you",
                null,
                null
        ));

        AiGenerationResponse response = client.generate(sourceRequest(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("embedded content");
    }

    @Test
    void doesNotExposeRawReasoningContentWhenContentExists() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));
        String rawReasoning = "private chain of thought SECRET_REASONING "
                + sourceJson("reasoning response");

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                sourceJson("content response"),
                rawReasoning,
                sourceJson("text response")
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("content response");
        assertThat(response.toString()).doesNotContain("SECRET_REASONING");
    }

    @Test
    void extractsJsonFromReasoningContentOnlyWhenContentIsBlank() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "   ",
                "internal reasoning that must not leak\nfinal answer:\n"
                        + sourceJson("reasoning json fallback"),
                "   "
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("reasoning json fallback");
        assertThat(response.toString()).doesNotContain("internal reasoning");
    }

    @Test
    void blankContentFallsBackToChoiceTextBeforeReasoningContent() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "   ",
                sourceJson("reasoning response"),
                sourceJson("text response")
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("text response");
    }

    @Test
    void addsCompanyLlmOutputTruncatedWhenFinishReasonIsLength() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "length",
                sourceJson("truncated response"),
                null,
                null
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.warnings())
                .contains(CompanyLlmProviderClient.COMPANY_LLM_OUTPUT_TRUNCATED);
    }

    @Test
    void addsCompanyLlmEmptyResponseWhenNoOutputExists() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "   ",
                "reasoning without usable json",
                "   "
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.errorCategory()).isEqualTo("EMPTY_RESPONSE");
        assertThat(response.warnings())
                .contains(CompanyLlmProviderClient.COMPANY_LLM_EMPTY_RESPONSE);
        assertThat(response.toString()).doesNotContain("reasoning without usable json");
    }

    @Test
    void shouldHandleInvalidJsonGracefully() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client("""
                {"choices":[{"message":{"content":"not json"}}]}
                """);

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.errorCategory()).isEqualTo("INVALID_JSON");
        assertThat(response.parseErrorCategory())
                .isEqualTo("NON_JSON_RESPONSE");
        assertThat(response.outputPreview()).contains("not json");
        assertThat(response.responseShape())
                .containsEntry("hasContent", true)
                .containsEntry("extractionSource", "content")
                .containsEntry("parseErrorCategory", "NON_JSON_RESPONSE");
        assertThat(response.errorMessage()).doesNotContain(SECRET);
    }

    @Test
    void invalidJsonPreviewIsSanitizedAndCapped() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));
        String invalidOutput = "not json password=abc "
                + "x".repeat(700)
                + "\n at com.example.Secret.line(Secret.java:42)";

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                invalidOutput,
                null,
                null
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.parseErrorCategory())
                .isEqualTo("NON_JSON_RESPONSE");
        assertThat(response.outputPreview()).hasSizeLessThanOrEqualTo(500);
        assertThat(response.outputPreview()).doesNotContain("password=abc");
        assertThat(response.outputPreview()).doesNotContain("Secret.java");
    }

    @Test
    void invalidReasoningContentDoesNotExposeRawReasoningPreview() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client(chatResponse(
                "stop",
                "   ",
                "private reasoning_content secret {not-valid-json}",
                "   "
        ));

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.parseErrorCategory())
                .isEqualTo("JSON_EXTRACTION_FAILED");
        assertThat(response.outputPreview()).doesNotContain("private reasoning");
        assertThat(response.outputPreview()).doesNotContain("reasoning_content");
    }

    @Test
    void shouldHandleTimeoutGracefully() {
        UUID caseId = UUID.randomUUID();
        properties.getAi().getCompany().setTimeoutMs(1);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = clientWithNeverResponse();
        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(response.errorMessage()).doesNotContain(SECRET);
    }

    @Test
    void shouldHandleUnauthorizedWithoutExposingToken() {
        CompanyLlmProviderClient client = clientWithStatus(
                HttpStatus.UNAUTHORIZED,
                "token=" + SECRET
        );

        AiConnectivityResult result = client.connectivity();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(401);
        assertThat(result.tokenConfigured()).isTrue();
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void shouldHandleForbiddenWithoutExposingToken() {
        CompanyLlmProviderClient client = clientWithStatus(
                HttpStatus.FORBIDDEN,
                "Authorization: Bearer " + SECRET
        );

        AiConnectivityResult result = client.connectivity();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(403);
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void shouldReportConnectivityWithoutTokenValue() {
        CompanyLlmProviderClient client = client(okResponse());

        AiConnectivityResult result = client.connectivity();

        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("COMPANY_LLM");
        assertThat(result.baseUrlConfigured()).isTrue();
        assertThat(result.tokenConfigured()).isTrue();
        assertThat(result.reachable()).isTrue();
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void liteLlmConnectivityRejectsPlainModelNameBeforeHttpCall() {
        configureLiteLlm();
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse
                            .create(HttpStatus.UNAUTHORIZED)
                            .header("Content-Type", "text/plain")
                            .body("unauthorized")
                            .build());
                });
        CompanyLlmProviderClient client = new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.connectivity(null, "gpt-3.5-turbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NAME_MUST_USE_OPENAI_PREFIX");
        assertThat(calls.get()).isZero();
    }

    @Test
    void liteLlmRoutingFromBaseUrlRejectsPlainModelNameBeforeHttpCall() {
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getLlm().setProvider(AiProviderType.DISABLED);
        properties.getLlm().setBaseUrl("https://llm.example.test");
        properties.getLlm().setDefaultModelName("openai/gpt-3.5-turbo");
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse
                            .create(HttpStatus.UNAUTHORIZED)
                            .header("Content-Type", "text/plain")
                            .body("unauthorized")
                            .build());
                });
        CompanyLlmProviderClient client = new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.connectivity(null, "gpt-3.5-turbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NAME_MUST_USE_OPENAI_PREFIX");
        assertThat(calls.get()).isZero();
    }

    private CompanyLlmProviderClient client(String body) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse
                        .create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()));
        return new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );
    }

    private CompanyLlmProviderClient clientWithStatus(
            HttpStatus status,
            String body
    ) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse
                        .create(status)
                        .header("Content-Type", "text/plain")
                        .body(body)
                        .build()));
        return new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );
    }

    private CompanyLlmProviderClient clientWithNeverResponse() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.never());
        return new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );
    }

    private String okResponse() {
        return """
                {
                  "id": "chatcmpl-test",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {
                        "content": "{\\"status\\":\\"HYPOTHESIS\\",\\"probableRootCause\\":\\"API validation mismatch\\",\\"confidence\\":0.42,\\"facts\\":[\\"fact\\"],\\"inferences\\":[\\"inference\\"],\\"unknowns\\":[\\"unknown\\"],\\"missingEvidence\\":[\\"trace\\"],\\"recommendedActions\\":[\\"review\\"],\\"warnings\\":[]}"
                      }
                    }
                  ]
                }
                """;
    }

    private String chatResponse(
            String finishReason,
            String content,
            String reasoningContent,
            String text
    ) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("content", content);
        if (reasoningContent != null) {
            message.put("reasoning_content", reasoningContent);
        }

        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("finish_reason", finishReason);
        choice.put("message", message);
        if (text != null) {
            choice.put("text", text);
        }

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", "chatcmpl-test");
        response.put("choices", List.of(choice));
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String chatResponseWithUsage(
            String finishReason,
            String content,
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("content", content);
        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("finish_reason", finishReason);
        choice.put("message", message);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", "chatcmpl-test");
        response.put("choices", List.of(choice));
        response.put("usage", Map.of(
                "prompt_tokens", promptTokens,
                "completion_tokens", completionTokens,
                "total_tokens", totalTokens
        ));
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sourceJson(String probableRootCause) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "HYPOTHESIS",
                    "probableRootCause", probableRootCause,
                    "confidence", 0.42,
                    "facts", List.of("fact"),
                    "inferences", List.of("inference"),
                    "unknowns", List.of("unknown"),
                    "missingEvidence", List.of("trace"),
                    "recommendedActions", List.of("review"),
                    "warnings", List.of()
            ));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String quoted(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AiGenerationRequest request(UUID caseId) {
        return new AiGenerationRequest(
                caseId,
                "FULL_INCIDENT_ANALYSIS",
                "system",
                "user",
                "company-model",
                0.1,
                2000,
                true,
                Map.of("jiraKey", "FIZZMS-10228")
        );
    }

    private AiGenerationRequest sourceRequest(UUID caseId) {
        return new AiGenerationRequest(
                caseId,
                "SOURCE_CHANGE_ANALYSIS",
                "Return only compact JSON.",
                "{\"contextMode\":\"MINIMAL\"}",
                "company-model",
                0.1,
                500,
                true,
                Map.of("requestType", "SOURCE_CHANGE_ANALYSIS")
        );
    }

    private AiGenerationRequest sourceRequestWithMetadata(
            Map<String, String> metadata
    ) {
        return new AiGenerationRequest(
                UUID.randomUUID(),
                metadata.getOrDefault("requestType", "SOURCE_CHANGE_ANALYSIS"),
                "Return only compact JSON.",
                "{\"contextMode\":\"MINIMAL\"}",
                "openai/gpt-4o-mini",
                0.1,
                500,
                true,
                metadata
        );
    }

    private void configureLiteLlm() {
        properties.getAi().setProvider(AiProviderType.LITELLM_OPENAI_COMPATIBLE);
        properties.getLlm().setProvider(AiProviderType.LITELLM_OPENAI_COMPATIBLE);
        properties.getLlm().setBaseUrl("https://llm.example.test");
        properties.getLlm().setApiKeyEnv("__MISSING_COMPANY_LLM_API_KEY__");
        properties.getLlm().setDefaultModelName("openai/gpt-3.5-turbo");
        properties.getLlm().setAllowedModelNames(List.of(
                "openai/gpt-3.5-turbo",
                "openai/gpt-4o-mini",
                "openai/gpt-4o"
        ));
        properties.getLlm().setWeeklyBudgetUsd(200.0);
        properties.getLlm().setBudgetPeriod("WEEKLY");
        properties.getLlm().setBudgetTrackingEnabled(true);
    }

    private EvidenceEntity aiInputBundle(String content) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setEvidenceType(EvidenceType.AI_INPUT_BUNDLE);
        evidence.setContentText(content);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}
