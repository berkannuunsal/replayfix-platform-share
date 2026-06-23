package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.CodeChangeAdvisoryRequest;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import com.etiya.replayfix.domain.CodeChangeAdvisoryEntity;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;
import com.etiya.replayfix.repository.CodeChangeAdvisoryRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeChangeAdvisoryServiceTest {

    private ReplayCaseRepository caseRepository;
    private CodeChangeAdvisoryRepository advisoryRepository;
    private ReplayFixProperties properties;
    private AiProviderClientFactory providerFactory;
    private AiProviderClient provider;
    private ObjectMapper objectMapper;
    private CodeChangeAdvisoryService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        advisoryRepository = mock(CodeChangeAdvisoryRepository.class);
        properties = new ReplayFixProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getAi().getCompany().setModel("company-llm");
        properties.getAi().getCompany().setMaxOutputChars(1200);
        providerFactory = mock(AiProviderClientFactory.class);
        provider = mock(AiProviderClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new CodeChangeAdvisoryService(
                caseRepository,
                advisoryRepository,
                properties,
                providerFactory,
                objectMapper
        );
        caseId = UUID.randomUUID();
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(providerFactory.getProvider()).thenReturn(provider);
        when(advisoryRepository.save(any(CodeChangeAdvisoryEntity.class)))
                .thenAnswer(invocation -> {
                    CodeChangeAdvisoryEntity entity = invocation.getArgument(0);
                    entity.setId(UUID.randomUUID());
                    return entity;
                });
    }

    @Test
    void rejectsAuthorizationTokenPasswordAndPrivateKeyInSnippets() {
        List<String> snippets = List.of(
                "Authorization: Bearer abc",
                "String access_token = \"abc\";",
                "String password = \"abc\";",
                "privateKey: abc"
        );

        for (String snippet : snippets) {
            assertThatThrownBy(() -> service.advise(
                    caseId,
                    "BACKEND_METHOD",
                    true,
                    60,
                    12000,
                    request(snippet)
            ))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(exception -> assertThat(
                            ((ResponseStatusException) exception).getStatusCode()
                    ).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(providerFactory);
    }

    @Test
    void doesNotWriteFiles(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("OrderService.java");
        Files.writeString(sourceFile, "class OrderService {}\n");
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successResponse());

        service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                new CodeChangeAdvisoryRequest(
                        "Order status is wrong",
                        "Status is COMPLETE",
                        "Status stays PENDING",
                        sourceFile.toString(),
                        "OrderService",
                        "complete",
                        "JAVA",
                        "void complete(Order order) { order.setStatus(\"PENDING\"); }",
                        "",
                        "Sanitized log summary only",
                        List.of("advisory only")
                )
        );

        assertThat(Files.readString(sourceFile))
                .isEqualTo("class OrderService {}\n");
    }

    @Test
    void timeoutReturnsFallback() {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenThrow(new RuntimeException("timeout while waiting"));

        CodeChangeAdvisoryResponse response = service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                request("void complete() { }")
        );

        assertThat(response.llmUsed()).isFalse();
        assertThat(response.llmStatus()).isEqualTo("TIMEOUT");
        assertThat(response.deterministicFallbackReason())
                .isEqualTo("TIMEOUT");
        assertThat(response.shouldProceedToPatch()).isFalse();
    }

    @Test
    void invalidJsonReturnsFallback() {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(new AiGenerationResponse(
                        true,
                        "COMPANY_LLM",
                        "company-llm",
                        "req-1",
                        "completed",
                        10,
                        100,
                        10,
                        objectMapper.createObjectNode().put("unexpected", true),
                        List.of(),
                        null,
                        null,
                        null,
                        "",
                        0,
                        "CODE_ADVISORY",
                        "openai/gpt-4o-mini",
                        90,
                        12000,
                        3000,
                        true,
                        "WEEKLY",
                        200.0,
                        true,
                        10,
                        20,
                        30,
                        Map.of()
                ));

        CodeChangeAdvisoryResponse response = service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                request("void complete() { }")
        );

        assertThat(response.llmUsed()).isFalse();
        assertThat(response.llmStatus()).isEqualTo("INVALID_JSON");
        assertThat(response.deterministicFallbackReason())
                .isEqualTo("INVALID_JSON");
        assertThat(response.safePromptSummary())
                .containsEntry("budgetTrackingEnabled", true)
                .containsEntry("budgetPeriod", "WEEKLY")
                .containsEntry("weeklyBudgetUsd", 200.0)
                .containsEntry("estimatedUsageAvailable", true)
                .containsEntry("totalTokenCount", 30)
                .doesNotContainKey("monthlyBudgetUsd");
    }

    @Test
    void methodLevelResponseParsesSuccessfully() throws Exception {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successResponse());

        CodeChangeAdvisoryResponse response = service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                45,
                12000,
                request("void complete(Order order) { order.setStatus(\"PENDING\"); }")
        );

        assertThat(response.llmUsed()).isTrue();
        assertThat(response.llmStatus()).isEqualTo("SUCCESS");
        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.recommendedCodeChange().file())
                .isEqualTo("src/main/java/OrderService.java");
        assertThat(response.recommendedCodeChange().methodName())
                .isEqualTo("complete");
        assertThat(response.testSuggestions())
                .contains("Add a focused unit test for complete().");
        assertThat(response.safePromptSummary().toString())
                .doesNotContain("order.setStatus");
        verify(advisoryRepository).save(any(CodeChangeAdvisoryEntity.class));

        ArgumentCaptor<AiGenerationRequest> captor =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(captor.capture());
        assertThat(captor.getValue().metadata())
                .containsEntry("companyLlmTimeoutSeconds", "45");
    }

    @Test
    void backendMethodPassesCodeAdvisoryProfileMetadata() throws Exception {
        configureLiteLlm();
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successLiteLlmResponse("CODE_ADVISORY"));

        service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                45,
                12000,
                "CODE_ADVISORY",
                "openai/gpt-4o-mini",
                request("void complete() { }")
        );

        ArgumentCaptor<AiGenerationRequest> captor =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(captor.capture());
        assertThat(captor.getValue().metadata())
                .containsEntry("modelProfile", "CODE_ADVISORY")
                .containsEntry("modelName", "openai/gpt-4o-mini")
                .containsEntry("advisoryMode", "BACKEND_METHOD");
    }

    @Test
    void testSuggestionPassesTestSuggestionModeForProfileRouting()
            throws Exception {
        configureLiteLlm();
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successLiteLlmResponse("TEST_SUGGESTION"));

        service.advise(
                caseId,
                "TEST_SUGGESTION",
                true,
                45,
                12000,
                null,
                null,
                request("void complete() { }")
        );

        ArgumentCaptor<AiGenerationRequest> captor =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(captor.capture());
        assertThat(captor.getValue().metadata())
                .containsEntry("advisoryMode", "TEST_SUGGESTION");
    }

    @Test
    void plainLiteLlmModelNameReturnsSafeBadRequest() {
        configureLiteLlm();

        assertThatThrownBy(() -> service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                null,
                "gpt-3.5-turbo",
                request("void complete() { }")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NAME_MUST_USE_OPENAI_PREFIX");
        verifyNoInteractions(providerFactory);
    }

    @Test
    void unknownLiteLlmModelNameReturnsSafeBadRequest() {
        configureLiteLlm();

        assertThatThrownBy(() -> service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                null,
                "openai/not-allowed",
                request("void complete() { }")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NOT_ALLOWED");
        verifyNoInteractions(providerFactory);
    }

    @Test
    void plainModelNameIsRejectedWhenLiteLlmRoutingUsesBaseUrl() {
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getLlm().setProvider(AiProviderType.DISABLED);
        properties.getLlm().setBaseUrl("https://llm.example.test");
        properties.getLlm().setDefaultModelName("openai/gpt-3.5-turbo");

        assertThatThrownBy(() -> service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                null,
                "gpt-3.5-turbo",
                request("void complete() { }")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_NAME_MUST_USE_OPENAI_PREFIX");
        verifyNoInteractions(providerFactory);
    }

    @Test
    void safePromptSummaryIncludesLiteLlmMetadata() throws Exception {
        configureLiteLlm();
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successLiteLlmResponse("CODE_ADVISORY"));

        CodeChangeAdvisoryResponse response = service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                null,
                null,
                request("void complete() { }")
        );

        assertThat(response.safePromptSummary())
                .containsEntry("provider", "LITELLM_OPENAI_COMPATIBLE")
                .containsEntry("modelProfile", "CODE_ADVISORY")
                .containsEntry("effectiveModelName", "openai/gpt-4o-mini")
                .containsEntry("budgetTrackingEnabled", true)
                .containsEntry("budgetPeriod", "WEEKLY")
                .containsEntry("weeklyBudgetUsd", 200.0)
                .containsEntry("estimatedUsageAvailable", true)
                .containsEntry("totalTokenCount", 30);
        assertThat(response.safePromptSummary().toString())
                .doesNotContain("COMPANY_LLM_API_KEY");
        assertThat(response.safePromptSummary())
                .doesNotContainKey("monthlyBudgetUsd");
    }

    @Test
    void legacyMonthlyBudgetMetadataIsMappedToWeeklyAndNotExposed() {
        CodeChangeAdvisoryResultSummary summary =
                new CodeChangeAdvisoryResultSummary(
                        UUID.randomUUID(),
                        "BACKEND_METHOD",
                        true,
                        "SUCCESS",
                        "HYPOTHESIS",
                        "MEDIUM",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        "",
                        Map.of(
                                "budgetTrackingEnabled",
                                true,
                                "monthlyBudgetUsd",
                                200.0,
                                "totalTokenCount",
                                30
                        ),
                        null
                );

        assertThat(summary.budgetTrackingEnabled()).isTrue();
        assertThat(summary.budgetPeriod()).isEqualTo("WEEKLY");
        assertThat(summary.weeklyBudgetUsd()).isEqualTo(200.0);
        assertThat(summary.totalTokenCount()).isEqualTo(30);
        assertThat(summary.safePromptSummary())
                .containsEntry("budgetPeriod", "WEEKLY")
                .containsEntry("weeklyBudgetUsd", 200.0)
                .doesNotContainKey("monthlyBudgetUsd");
    }

    @Test
    void noRawReasoningContentIsExposed() throws Exception {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(new AiGenerationResponse(
                        true,
                        "COMPANY_LLM",
                        "company-llm",
                        "req-1",
                        "completed",
                        10,
                        100,
                        200,
                        objectMapper.readTree("""
                                {
                                  "status": "HYPOTHESIS",
                                  "confidence": "LOW",
                                  "reasoning_content": "raw chain",
                                  "recommendedCodeChange": {
                                    "file": "OrderService.java",
                                    "methodName": "complete",
                                    "changeType": "GUARD",
                                    "description": "Use sanitized state.",
                                    "pseudoPatch": "reasoning_content: raw chain"
                                  },
                                  "risks": [],
                                  "missingEvidence": [],
                                  "testSuggestions": [],
                                  "shouldProceedToPatch": false
                                }
                                """),
                        List.of(),
                        null,
                        null
                ));

        CodeChangeAdvisoryResponse response = service.advise(
                caseId,
                "BACKEND_METHOD",
                true,
                60,
                12000,
                request("void complete() { }")
        );

        assertThat(response.toString()).doesNotContain("raw chain");
        assertThat(response.toString()).contains("[REDACTED_REASONING_CONTENT]");
    }

    @Test
    void summaryEvaluatesLatestAdvisoriesWithoutRawPrompts() {
        when(advisoryRepository.findByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(List.of(
                        advisory(
                                "BACKEND_METHOD",
                                "HIGH",
                                "CONDITIONAL_MAPPING",
                                List.of(),
                                true
                        ),
                        advisory(
                                "FRONTEND_COMPONENT",
                                "MEDIUM",
                                "PROP_MAPPING",
                                List.of(),
                                false
                        ),
                        advisory(
                                "TEST_SUGGESTION",
                                "LOW",
                                "TEST_ONLY",
                                List.of("Replay execution not run"),
                                false
                        )
                ));

        CodeChangeAdvisoryEvaluationSummaryResponse response =
                service.summary(caseId);

        assertThat(response.advisoryGeneratedCount()).isEqualTo(3);
        assertThat(response.latestBackendMethodAdvisory()
                .advisoryMode()).isEqualTo("BACKEND_METHOD");
        assertThat(response.averageConfidence()).isEqualTo(0.63);
        assertThat(response.actionableRecommendationCount()).isEqualTo(3);
        assertThat(response.missingEvidenceCount()).isEqualTo(1);
        assertThat(response.shouldProceedToPatchCount()).isEqualTo(1);
        assertThat(response.caseAdvisoryStatus())
                .isEqualTo("ADVISORY_READY");
        assertThat(response.toString()).doesNotContain("rawPrompt");
        assertThat(response.toString()).doesNotContain("order.setStatus");
    }

    private AiGenerationResponse successResponse() throws Exception {
        return new AiGenerationResponse(
                true,
                "COMPANY_LLM",
                "company-llm",
                "req-1",
                "completed",
                10,
                100,
                200,
                objectMapper.readTree("""
                        {
                          "status": "HYPOTHESIS",
                          "confidence": "MEDIUM",
                          "recommendedCodeChange": {
                            "file": "src/main/java/OrderService.java",
                            "methodName": "complete",
                            "changeType": "CONDITIONAL_MAPPING",
                            "description": "Map successful completion to COMPLETE status.",
                            "pseudoPatch": "if (success) setStatus(COMPLETE);"
                          },
                          "risks": ["Status mapping may affect retry flow."],
                          "missingEvidence": ["Replay execution not run."],
                          "testSuggestions": ["Add a focused unit test for complete()."],
                          "shouldProceedToPatch": false
                        }
                        """),
                List.of(),
                null,
                null
        );
    }

    private AiGenerationResponse successLiteLlmResponse(String profile)
            throws Exception {
        return new AiGenerationResponse(
                true,
                "LITELLM_OPENAI_COMPATIBLE",
                "openai/gpt-4o-mini",
                "req-1",
                "completed",
                10,
                100,
                200,
                objectMapper.readTree("""
                        {
                          "status": "HYPOTHESIS",
                          "confidence": "MEDIUM",
                          "recommendedCodeChange": {
                            "file": "src/main/java/OrderService.java",
                            "methodName": "complete",
                            "changeType": "CONDITIONAL_MAPPING",
                            "description": "Map successful completion to COMPLETE status.",
                            "pseudoPatch": "if (success) setStatus(COMPLETE);"
                          },
                          "risks": [],
                          "missingEvidence": [],
                          "testSuggestions": [],
                          "shouldProceedToPatch": false
                        }
                        """),
                List.of(),
                null,
                null,
                null,
                "",
                3000,
                profile,
                "openai/gpt-4o-mini",
                90,
                12000,
                3000,
                true,
                "WEEKLY",
                200.0,
                true,
                10,
                20,
                30,
                Map.of()
        );
    }

    private void configureLiteLlm() {
        properties.getAi().setProvider(AiProviderType.LITELLM_OPENAI_COMPATIBLE);
        properties.getLlm().setProvider(AiProviderType.LITELLM_OPENAI_COMPATIBLE);
        properties.getLlm().setDefaultModelName("openai/gpt-3.5-turbo");
        properties.getLlm().setAllowedModelNames(List.of(
                "openai/gpt-3.5-turbo",
                "openai/gpt-4o-mini",
                "openai/gpt-4o"
        ));
    }

    private CodeChangeAdvisoryRequest request(String codeSnippet) {
        return new CodeChangeAdvisoryRequest(
                "Order completion status mismatch",
                "Order status becomes COMPLETE",
                "Order status remains PENDING",
                "src/main/java/OrderService.java",
                "OrderService",
                "complete",
                "JAVA",
                codeSnippet,
                "record OrderDto(String id, String status) {}",
                "Sanitized log summary only",
                List.of("advisory only")
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }

    private CodeChangeAdvisoryEntity advisory(
            String mode,
            String confidence,
            String changeType,
            List<String> missingEvidence,
            boolean shouldProceedToPatch
    ) {
        CodeChangeAdvisoryEntity entity = new CodeChangeAdvisoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setAdvisoryMode(mode);
        entity.setLlmUsed(true);
        entity.setLlmStatus("SUCCESS");
        entity.setStatus("HYPOTHESIS");
        entity.setConfidence(confidence);
        entity.setRecommendedFile("OrderService.java");
        entity.setRecommendedMethodName("complete");
        entity.setRecommendedChangeType(changeType);
        entity.setRecommendedDescription("Use method-level guard.");
        entity.setRecommendedPseudoPatch("pseudo patch only");
        entity.setRisksJson(json(List.of("risk")));
        entity.setMissingEvidenceJson(json(missingEvidence));
        entity.setTestSuggestionsJson(json(List.of("test suggestion")));
        entity.setShouldProceedToPatch(shouldProceedToPatch);
        entity.setDeterministicFallbackReason("");
        entity.setSafePromptSummaryJson(json(Map.of(
                "filePath",
                "OrderService.java",
                "codeSnippetChars",
                42
        )));
        entity.setResponseSnapshotJson("{}");
        return entity;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
