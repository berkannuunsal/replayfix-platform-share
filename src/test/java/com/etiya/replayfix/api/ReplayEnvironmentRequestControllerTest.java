package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.ReplayEnvironmentDemoSummaryResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentLlmAdvisoryResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisioningDisabledResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentRequestResponse;
import com.etiya.replayfix.service.ReplayEnvironmentLlmAdvisoryService;
import com.etiya.replayfix.service.ReplayEnvironmentRequestService;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayEnvironmentRequestControllerTest {

    @Test
    void provisionEndpointReturnsDisabled409() throws Exception {
        UUID requestId = UUID.randomUUID();
        ReplayEnvironmentRequestService service =
                mock(ReplayEnvironmentRequestService.class);
        when(service.provision(requestId)).thenReturn(
                new ReplayEnvironmentProvisioningDisabledResponse(
                        new ReplayEnvironmentRequestResponse(
                                requestId,
                                UUID.randomUUID(),
                                "FIZZMS-10228",
                                "bss-monolith",
                                "PROVISIONING_DISABLED",
                                "project-replay-sandbox",
                                "replay-fizzms-10228.example.test",
                                true,
                                false,
                                List.of("Human approval before ArgoCD provisioning"),
                                List.of(),
                                List.of("dry-run first"),
                                List.of(ReplayEnvironmentRequestService
                                        .PROVISIONING_DISABLED_NEXT_ACTION),
                                null,
                                null
                        ),
                        ReplayEnvironmentRequestService
                                .PROVISIONING_DISABLED_MESSAGE,
                        List.of(ReplayEnvironmentRequestService
                                .PROVISIONING_DISABLED_NEXT_ACTION)
                )
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentRequestController(
                        service,
                        mock(ReplayEnvironmentLlmAdvisoryService.class)
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post(
                        "/api/v1/replay-environment/requests/{requestId}/provision",
                        requestId
                ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        ReplayEnvironmentRequestService
                                .PROVISIONING_DISABLED_MESSAGE
                ))
                .andExpect(jsonPath("$.request.status").value(
                        "PROVISIONING_DISABLED"
                ))
                .andExpect(jsonPath("$.request.realProvisioningEnabled")
                        .value(false));

        verify(service).provision(requestId);
    }

    @Test
    void createWithDryRunFalseReturns400WithoutCallingService()
            throws Exception {
        ReplayEnvironmentRequestService service =
                mock(ReplayEnvironmentRequestService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentRequestController(
                        service,
                        mock(ReplayEnvironmentLlmAdvisoryService.class)
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/replay-environment/requests",
                        UUID.randomUUID()
                ).param("dryRun", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Real ArgoCD provisioning is not enabled yet. Use dryRun=true."
                ));

        verifyNoInteractions(service);
    }

    @Test
    void provisionReadinessEndpointReturnsReadiness() throws Exception {
        UUID requestId = UUID.randomUUID();
        ReplayEnvironmentRequestService service =
                mock(ReplayEnvironmentRequestService.class);
        when(service.provisionReadiness(eq(requestId))).thenReturn(
                new ReplayEnvironmentProvisionReadinessResponse(
                        requestId,
                        UUID.randomUUID(),
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
                        "project-replay-sandbox",
                        "replay-fizzms-10228.example.test",
                        List.of("REAL_PROVISIONING_DISABLED"),
                        List.of(),
                        List.of(ReplayEnvironmentRequestService
                                .PROVISIONING_DISABLED_NEXT_ACTION),
                        List.of("dry-run first"),
                        Instant.parse("2026-06-23T06:00:00Z")
                )
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentRequestController(
                        service,
                        mock(ReplayEnvironmentLlmAdvisoryService.class)
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get(
                        "/api/v1/replay-environment/requests/{requestId}/provision-readiness",
                        requestId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.readinessStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.blockers[0]").value(
                        "REAL_PROVISIONING_DISABLED"
                ));
    }

    @Test
    void demoSummaryEndpointReturnsOneScreenSummary() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        ReplayEnvironmentRequestService service =
                mock(ReplayEnvironmentRequestService.class);
        when(service.demoSummary(eq(requestId))).thenReturn(
                new ReplayEnvironmentDemoSummaryResponse(
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
                        List.of("REAL_PROVISIONING_DISABLED"),
                        List.of("dry-run first"),
                        List.of(ReplayEnvironmentRequestService
                                .PROVISIONING_DISABLED_NEXT_ACTION),
                        "ReplayFix has prepared a dry-run replay environment plan."
                )
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentRequestController(
                        service,
                        mock(ReplayEnvironmentLlmAdvisoryService.class)
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get(
                        "/api/v1/replay-environment/requests/{requestId}/demo-summary",
                        requestId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jiraKey").value("FIZZMS-10228"))
                .andExpect(jsonPath("$.targetKey").value("bss-monolith"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.backendReplayApp")
                        .value("bss-backend-replay"))
                .andExpect(jsonPath("$.sanitizedInputAttached").value(true))
                .andExpect(jsonPath("$.blockers[0]")
                        .value("REAL_PROVISIONING_DISABLED"))
                .andExpect(jsonPath("$.demoNarrative")
                        .value("ReplayFix has prepared a dry-run replay environment plan."));
    }

    @Test
    void llmAdvisoryEndpointReturnsAdvisory() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        ReplayEnvironmentRequestService service =
                mock(ReplayEnvironmentRequestService.class);
        ReplayEnvironmentLlmAdvisoryService advisoryService =
                mock(ReplayEnvironmentLlmAdvisoryService.class);
        when(advisoryService.advise(
                eq(requestId),
                eq("GO_NO_GO"),
                eq(false),
                any()
        )).thenReturn(new ReplayEnvironmentLlmAdvisoryResponse(
                requestId,
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "GO_NO_GO",
                false,
                "NOT_REQUESTED",
                "FALLBACK",
                Map.of(
                        "overallAssessment",
                        "ADVISORY: provisioning is blocked.",
                        "goNoGoForRealProvisioning",
                        Map.of(
                                "ready",
                                false,
                                "blockers",
                                List.of("REAL_PROVISIONING_DISABLED"),
                                "requiredApprovals",
                                List.of()
                        )
                ),
                List.of("REAL_PROVISIONING_DISABLED"),
                List.of(),
                List.of(ReplayEnvironmentRequestService
                        .PROVISIONING_DISABLED_NEXT_ACTION),
                "{\"overallAssessment\":\"ADVISORY\"}",
                Instant.parse("2026-06-23T06:00:00Z")
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentRequestController(
                        service,
                        advisoryService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post(
                        "/api/v1/replay-environment/requests/{requestId}/llm-advisory",
                        requestId
                )
                        .param("advisoryMode", "GO_NO_GO")
                        .param("useCompanyLlm", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Can we provision?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.advisoryMode").value("GO_NO_GO"))
                .andExpect(jsonPath("$.llmUsed").value(false))
                .andExpect(jsonPath("$.advisoryStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.blockers[0]")
                        .value("REAL_PROVISIONING_DISABLED"));

        verify(advisoryService).advise(
                eq(requestId),
                eq("GO_NO_GO"),
                eq(false),
                any()
        );
    }
}
