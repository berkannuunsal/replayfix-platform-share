package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;
import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanySourceReasoningServiceTest {

    private ReplayFixProperties properties;
    private AiProviderClientFactory factory;
    private AiProviderClient provider;
    private CompanySourceReasoningService service;

    @BeforeEach
    void setUp() {
        properties = new ReplayFixProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getAi().getCompany().setModel("AI-Coder-PR-Review");
        properties.getAi().setTemperature(0.1);
        factory = mock(AiProviderClientFactory.class);
        provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        service = new CompanySourceReasoningService(
                properties,
                factory,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void validSourceReasoningJsonReturnsLlmUsed() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                true,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                "request-1",
                "stop",
                25,
                10,
                10,
                new ObjectMapper().valueToTree(Map.of(
                        "status", "CONFIRMED",
                        "confidence", 0.8,
                        "suspectChanges", List.of(Map.of(
                                "file", "A.java",
                                "className", "A",
                                "methodName", "m",
                                "layer", "SERVICE",
                                "suspectReason", "reason",
                                "confidence", 0.8
                        ))
                )),
                List.of(),
                null,
                null
        ));

        var result = service.reason(UUID.randomUUID(), context());

        assertThat(result.llmUsed()).isTrue();
        assertThat(result.status()).isEqualTo("HYPOTHESIS");
        assertThat(result.suspectChanges()).hasSize(1);
        assertThat(result.suspectChanges().get(0).status())
                .isEqualTo("HYPOTHESIS");
        assertThat(result.warnings())
                .contains(CompanySourceReasoningService
                        .COMPANY_LLM_STATUS_DOWNGRADED);
    }

    @Test
    void missingMinimalArraysDefaultToEmptyLists() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                true,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                "request-1",
                "stop",
                25,
                10,
                10,
                new ObjectMapper().valueToTree(Map.of(
                        "status", "HYPOTHESIS",
                        "confidence", 2.0,
                        "suspectReason", "reason"
                )),
                List.of(),
                null,
                null
        ));

        var result = service.reason(
                UUID.randomUUID(),
                "{\"contextMode\":\"MINIMAL\"}",
                500,
                "MINIMAL"
        );

        assertThat(result.llmUsed()).isTrue();
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.facts()).isEmpty();
        assertThat(result.inferences()).isEmpty();
        assertThat(result.unknowns()).isEmpty();
        assertThat(result.missingEvidence()).isEmpty();
    }

    @Test
    void invalidJsonCategoryReturnsInvalidResponseWarning() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                false,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                null,
                "error",
                10,
                0,
                0,
                null,
                List.of(),
                "INVALID_JSON",
                "not valid JSON"
        ));

        var result = service.reason(UUID.randomUUID(), context());

        assertThat(result.llmUsed()).isFalse();
        assertThat(result.warnings())
                .contains(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE);
        assertThat(result.parseErrorCategory())
                .isEqualTo("NON_JSON_RESPONSE");
    }

    @Test
    void emptyMinimalResponseReturnsInvalidAndEmptyResponseWarnings() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                false,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                null,
                "length",
                10,
                0,
                0,
                null,
                List.of(
                        CompanySourceReasoningService.COMPANY_LLM_EMPTY_RESPONSE,
                        "COMPANY_LLM_OUTPUT_TRUNCATED"
                ),
                "EMPTY_RESPONSE",
                "empty response"
        ));

        var result = service.reason(
                UUID.randomUUID(),
                "{\"contextMode\":\"MINIMAL\"}",
                500,
                "MINIMAL"
        );

        assertThat(result.llmUsed()).isFalse();
        assertThat(result.warnings())
                .contains(
                        CompanySourceReasoningService
                                .COMPANY_LLM_EMPTY_RESPONSE,
                        CompanySourceReasoningService
                                .COMPANY_LLM_INVALID_RESPONSE,
                        "COMPANY_LLM_OUTPUT_TRUNCATED"
                )
                .doesNotContain(CompanySourceReasoningService
                        .COMPANY_LLM_UNAVAILABLE);
        assertThat(result.parseErrorCategory()).isEqualTo("EMPTY_RESPONSE");
        assertThat(result.effectiveOutputTokenLimit()).isEqualTo(500);
    }

    @Test
    void invalidResponseCarriesSanitizedOutputPreviewAndEffectiveLimit() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                false,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                null,
                "error",
                10,
                0,
                0,
                null,
                List.of(),
                "INVALID_JSON",
                "not valid JSON",
                "NON_JSON_RESPONSE",
                "not json token=secret-value " + "x".repeat(700),
                1000
        ));

        var result = service.reason(
                UUID.randomUUID(),
                "{\"contextMode\":\"MINIMAL\"}",
                1000,
                "MINIMAL"
        );

        assertThat(result.llmUsed()).isFalse();
        assertThat(result.parseErrorCategory())
                .isEqualTo("NON_JSON_RESPONSE");
        assertThat(result.outputPreview()).contains("not json");
        assertThat(result.outputPreview()).doesNotContain("secret-value");
        assertThat(result.outputPreview()).hasSizeLessThanOrEqualTo(500);
        assertThat(result.effectiveOutputTokenLimit()).isEqualTo(1000);
    }

    @Test
    void http503CategoryReturnsUnavailableWarning() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                false,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                null,
                "error",
                10,
                0,
                0,
                null,
                List.of(),
                "HTTP_503",
                "service unavailable"
        ));

        var result = service.reason(UUID.randomUUID(), context());

        assertThat(result.llmUsed()).isFalse();
        assertThat(result.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
    }

    @Test
    void minimalSourceReasoningRequestUsesOutputTokenLimit() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                true,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                "request-1",
                "stop",
                25,
                10,
                10,
                new ObjectMapper().valueToTree(Map.of(
                        "status", "HYPOTHESIS",
                        "confidence", 0.4,
                        "facts", List.of("fact")
                )),
                List.of(),
                null,
                null
        ));

        service.reason(
                UUID.randomUUID(),
                "{\"contextMode\":\"MINIMAL\"}",
                500,
                "MINIMAL"
        );

        ArgumentCaptor<AiGenerationRequest> request =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(request.capture());
        assertThat(request.getValue().requestType())
                .isEqualTo("SOURCE_CHANGE_ANALYSIS");
        assertThat(request.getValue().maxOutputChars()).isEqualTo(500);
        assertThat(request.getValue().systemPrompt())
                .contains("Return only one valid JSON object")
                .contains("No text before or after JSON")
                .contains("Never return CONFIRMED");
        assertThat(request.getValue().userPrompt())
                .contains("Given this small ReplayFix evidence packet")
                .contains("\"status\": \"HYPOTHESIS\"");
    }

    @Test
    void successfulMinimalSourceReasoningJsonParsesWithThreeThousandTokens() {
        when(provider.generate(any())).thenReturn(new AiGenerationResponse(
                true,
                "COMPANY_LLM",
                "AI-Coder-PR-Review",
                "request-1",
                "stop",
                25,
                10,
                10,
                new ObjectMapper().valueToTree(Map.of(
                        "status", "HYPOTHESIS",
                        "confidence", 0.6,
                        "suspectReason", "service validation guard",
                        "facts", List.of("UserServiceImpl#updateUser")
                )),
                List.of(),
                null,
                null,
                null,
                "",
                3_000
        ));

        var result = service.reason(
                UUID.randomUUID(),
                "{\"contextMode\":\"MINIMAL\"}",
                3_000,
                "MINIMAL"
        );

        ArgumentCaptor<AiGenerationRequest> request =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(request.capture());
        assertThat(request.getValue().maxOutputChars()).isEqualTo(3_000);
        assertThat(result.llmUsed()).isTrue();
        assertThat(result.status()).isEqualTo("HYPOTHESIS");
        assertThat(result.effectiveOutputTokenLimit()).isEqualTo(3_000);
        assertThat(result.warnings()).doesNotContain(
                CompanySourceReasoningService.COMPANY_LLM_INVALID_RESPONSE
        );
    }

    private SourceReasoningContext context() {
        return new SourceReasoningContext(
                Map.of(),
                Map.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
