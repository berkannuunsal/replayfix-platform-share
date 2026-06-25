package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayEnvironmentDemoSummaryResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentLlmAdvisoryRequest;
import com.etiya.replaylab.api.dto.ReplayEnvironmentLlmAdvisoryResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentReadiness;
import com.etiya.replaylab.api.dto.ReplayEnvironmentRequestResponse;
import com.etiya.replaylab.api.dto.ReplayRuntimeDependencyPlan;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.AiProviderType;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import com.etiya.replaylab.service.ai.AiProviderClient;
import com.etiya.replaylab.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplayEnvironmentLlmAdvisoryServiceTest {

    private ReplayEnvironmentRequestService requestService;
    private ReplayLabProperties properties;
    private AiProviderClientFactory providerFactory;
    private AiProviderClient provider;
    private ObjectMapper objectMapper;
    private ReplayEnvironmentLlmAdvisoryService service;
    private UUID requestId;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        requestService = mock(ReplayEnvironmentRequestService.class);
        properties = new ReplayLabProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getAi().getCompany().setModel("company-llm");
        properties.getAi().getCompany().setMaxOutputChars(1200);
        providerFactory = mock(AiProviderClientFactory.class);
        provider = mock(AiProviderClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ReplayEnvironmentLlmAdvisoryService(
                requestService,
                properties,
                providerFactory,
                objectMapper
        );
        requestId = UUID.randomUUID();
        caseId = UUID.randomUUID();
        when(providerFactory.getProvider()).thenReturn(provider);
        when(requestService.get(requestId)).thenReturn(requestResponse());
        when(requestService.getPlan(requestId)).thenReturn(plan());
        when(requestService.provisionReadiness(requestId))
                .thenReturn(readiness());
        when(requestService.demoSummary(requestId)).thenReturn(demo());
    }

    @Test
    void promptDoesNotContainSecretValuesRawRequestBodyOrAuthHeaders()
            throws Exception {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successResponse());

        service.advise(
                requestId,
                "ARCHITECTURE_REVIEW",
                true,
                new ReplayEnvironmentLlmAdvisoryRequest(
                        "Review this Authorization: Bearer super-secret-token",
                        List.of("Cookie: SESSION=secret-session"),
                        true,
                        true,
                        true
                )
        );

        ArgumentCaptor<AiGenerationRequest> captor =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(captor.capture());
        String prompt = captor.getValue().userPrompt();

        assertThat(prompt)
                .contains("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("super-secret-token")
                .doesNotContain("secret-session")
                .doesNotContain("sanitizedRequestBody")
                .doesNotContain("cardNumber");
    }

    @Test
    void runtimeDependencyBlockersAreIncluded() throws Exception {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenReturn(successResponse());

        ReplayEnvironmentLlmAdvisoryResponse response = service.advise(
                requestId,
                "RUNTIME_DEPENDENCY_REVIEW",
                true,
                new ReplayEnvironmentLlmAdvisoryRequest(
                        "Review runtime safety",
                        List.of("ActiveMQ"),
                        true,
                        true,
                        true
                )
        );

        assertThat(response.blockers())
                .contains("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING");
        ArgumentCaptor<AiGenerationRequest> captor =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(provider).generate(captor.capture());
        assertThat(captor.getValue().userPrompt())
                .contains("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING");
    }

    @Test
    void llmTimeoutReturnsFallback() {
        when(provider.generate(any(AiGenerationRequest.class)))
                .thenThrow(new RuntimeException("timeout while waiting"));

        ReplayEnvironmentLlmAdvisoryResponse response = service.advise(
                requestId,
                "ARCHITECTURE_REVIEW",
                true,
                new ReplayEnvironmentLlmAdvisoryRequest(
                        "Review readiness",
                        List.of(),
                        true,
                        true,
                        true
                )
        );

        assertThat(response.llmUsed()).isFalse();
        assertThat(response.advisoryStatus()).isEqualTo("TIMEOUT");
        assertThat(response.advisory()).containsKey("overallAssessment");
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
                        null
                ));

        ReplayEnvironmentLlmAdvisoryResponse response = service.advise(
                requestId,
                "ARCHITECTURE_REVIEW",
                true,
                new ReplayEnvironmentLlmAdvisoryRequest(
                        "Review readiness",
                        List.of(),
                        true,
                        true,
                        true
                )
        );

        assertThat(response.llmUsed()).isFalse();
        assertThat(response.advisoryStatus()).isEqualTo("INVALID_JSON");
        assertThat(response.warnings()).contains("COMPANY_LLM_INVALID_JSON");
    }

    @Test
    void goNoGoModeIncludesBlockersWithoutCallingLlmWhenDisabled() {
        ReplayEnvironmentLlmAdvisoryResponse response = service.advise(
                requestId,
                "GO_NO_GO",
                false,
                new ReplayEnvironmentLlmAdvisoryRequest(
                        "Can we provision?",
                        List.of(),
                        true,
                        true,
                        true
                )
        );

        Map<?, ?> goNoGo = (Map<?, ?>) response.advisory()
                .get("goNoGoForRealProvisioning");
        assertThat(response.llmUsed()).isFalse();
        assertThat(response.advisoryStatus()).isEqualTo("FALLBACK");
        assertThat(goNoGo.get("blockers").toString())
                .contains("REAL_PROVISIONING_DISABLED");
        verifyNoInteractions(providerFactory);
    }

    @Test
    void nextCodexTaskModeReturnsRecommendedTask() {
        ReplayEnvironmentLlmAdvisoryResponse response = service.advise(
                requestId,
                "NEXT_CODEX_TASK",
                false,
                new ReplayEnvironmentLlmAdvisoryRequest(
                        "What should Codex do next?",
                        List.of(),
                        true,
                        true,
                        true
                )
        );

        assertThat((List<?>) response.advisory()
                .get("recommendedCodexTasks")).isNotEmpty();
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
                          "overallAssessment": "ADVISORY: blockers remain.",
                          "mainRisks": [],
                          "questionsForTeams": {
                            "infra": [],
                            "security": [],
                            "dba": [],
                            "backend": [],
                            "frontend": []
                          },
                          "recommendedNextSteps": [],
                          "recommendedCodexTasks": [
                            {
                              "taskName": "Resolve blockers",
                              "goal": "Clear readiness blockers",
                              "whyNow": "Provisioning is blocked",
                              "mustNotDo": []
                            }
                          ],
                          "goNoGoForRealProvisioning": {
                            "ready": false,
                            "blockers": ["REAL_PROVISIONING_DISABLED"],
                            "requiredApprovals": []
                          }
                        }
                        """),
                List.of(),
                null,
                null
        );
    }

    private ReplayEnvironmentRequestResponse requestResponse() {
        return new ReplayEnvironmentRequestResponse(
                requestId,
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "APPROVED",
                "project-replay-sandbox",
                "replay-fizzms-10228.example.test",
                true,
                false,
                List.of("Human approval before ArgoCD provisioning"),
                List.of("REAL_PROVISIONING_DISABLED"),
                List.of("dry-run first", "no secret value exposure"),
                List.of(ReplayEnvironmentRequestService
                        .PROVISIONING_DISABLED_NEXT_ACTION),
                null,
                null
        );
    }

    private ReplayEnvironmentPlanResponse plan() {
        return new ReplayEnvironmentPlanResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "READY_FOR_APPROVAL",
                "Plan ready",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(runtimeDependency()),
                null,
                new ReplayEnvironmentReadiness(
                        true,
                        true,
                        false,
                        false,
                        List.of("REAL_PROVISIONING_DISABLED")
                ),
                List.of("Human approval before ArgoCD provisioning"),
                List.of(),
                List.of("dry-run first", "no secret value exposure"),
                List.of("Review runtime dependency blockers"),
                Instant.parse("2026-06-23T06:00:00Z")
        );
    }

    private ReplayRuntimeDependencyPlan runtimeDependency() {
        return new ReplayRuntimeDependencyPlan(
                "activeMq",
                "ACTIVEMQ",
                "DISABLED",
                "BSS_ACTIVEMQ_URL",
                List.of("BSS_MQ_LISTENER_ENABLED"),
                List.of("BSS_ACTIVEMQ_PASSWORD"),
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                List.of("BSS_MQ_LISTENER_ENABLED=false"),
                List.of("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING"),
                List.of("ACTIVEMQ_CONNECTION_MAY_STILL_BE_REQUIRED_FOR_STARTUP"),
                List.of("Configure listener disable key")
        );
    }

    private ReplayEnvironmentProvisionReadinessResponse readiness() {
        return new ReplayEnvironmentProvisionReadinessResponse(
                requestId,
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "APPROVED",
                "BLOCKED",
                true,
                false,
                true,
                true,
                false,
                true,
                true,
                false,
                false,
                true,
                true,
                false,
                "project-replay-sandbox",
                "replay-fizzms-10228.example.test",
                List.of("REAL_PROVISIONING_DISABLED",
                        "ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING"),
                List.of("ARGOCD_CONFIG_DISABLED"),
                List.of("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING"),
                List.of("ACTIVEMQ_CONNECTION_MAY_STILL_BE_REQUIRED_FOR_STARTUP"),
                List.of(ReplayEnvironmentRequestService
                        .PROVISIONING_DISABLED_NEXT_ACTION),
                List.of("dry-run first", "no secret value exposure"),
                Instant.parse("2026-06-23T06:00:00Z")
        );
    }

    private ReplayEnvironmentDemoSummaryResponse demo() {
        return new ReplayEnvironmentDemoSummaryResponse(
                "FIZZMS-10228",
                "bss-monolith",
                caseId,
                requestId,
                "APPROVED",
                "READY_FOR_APPROVAL",
                "BLOCKED",
                "bss-backend-replay",
                "frontend-customer-ui-replay",
                "project-replay-sandbox",
                "replay-fizzms-10228.example.test",
                "SINGLE_HOST_PATH_BASED",
                "TEST2_SHARED_DB",
                true,
                false,
                List.of("REAL_PROVISIONING_DISABLED"),
                List.of("ACTIVEMQ_DISABLE_CONFIG_KEY_MISSING"),
                List.of("ACTIVEMQ_CONNECTION_MAY_STILL_BE_REQUIRED_FOR_STARTUP"),
                List.of("dry-run first", "no secret value exposure"),
                List.of(ReplayEnvironmentRequestService
                        .PROVISIONING_DISABLED_NEXT_ACTION),
                "ReplayLab has prepared a dry-run replay environment plan."
        );
    }
}
