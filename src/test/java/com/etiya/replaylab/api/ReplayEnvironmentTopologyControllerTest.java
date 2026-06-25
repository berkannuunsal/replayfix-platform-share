package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintInput;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintResult;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintsRequest;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintsResponse;
import com.etiya.replaylab.api.dto.ReplayEnvironmentTopologyComponentPlan;
import com.etiya.replaylab.api.dto.ReplayEnvironmentTopologyPlanResponse;
import com.etiya.replaylab.service.ReplayEnvironmentComponentHintService;
import com.etiya.replaylab.service.ReplayEnvironmentTopologyPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayEnvironmentTopologyControllerTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void postsComponentHints() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayEnvironmentComponentHintService hintService =
                mock(ReplayEnvironmentComponentHintService.class);
        ReplayEnvironmentTopologyPlanService topologyService =
                mock(ReplayEnvironmentTopologyPlanService.class);
        when(hintService.addHints(eq(caseId), any()))
                .thenReturn(hintsResponse(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentTopologyController(
                        hintService,
                        topologyService
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/replay-environment/component-hints",
                        caseId
                )
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new ReplayEnvironmentComponentHintsRequest(
                                        List.of(new ReplayEnvironmentComponentHintInput(
                                                "mco-backend",
                                                "REPLAY_COPY",
                                                "manual"
                                        )),
                                        "notes"
                                )
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedHints[0].componentKey")
                        .value("mco-backend"));
    }

    @Test
    void getsTopologyPlan() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReplayEnvironmentComponentHintService hintService =
                mock(ReplayEnvironmentComponentHintService.class);
        ReplayEnvironmentTopologyPlanService topologyService =
                mock(ReplayEnvironmentTopologyPlanService.class);
        when(topologyService.plan(eq(caseId), eq(true), eq(true), eq(true)))
                .thenReturn(topologyResponse(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayEnvironmentTopologyController(
                        hintService,
                        topologyService
                ))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/replay-environment/topology-plan",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("project-replay-sandbox"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.components[0].replayApplicationName")
                        .value("replay-fizzms-10228-backend"))
                .andExpect(jsonPath("$.guardrails[0]").value("DRY_RUN_ONLY"));
    }

    private ReplayEnvironmentComponentHintsResponse hintsResponse(UUID caseId) {
        return new ReplayEnvironmentComponentHintsResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "ACCEPTED",
                List.of(new ReplayEnvironmentComponentHintResult(
                        "mco-backend",
                        "REPLAY_COPY",
                        "manual",
                        "ACCEPTED"
                )),
                List.of(),
                List.of()
        );
    }

    private ReplayEnvironmentTopologyPlanResponse topologyResponse(UUID caseId) {
        return new ReplayEnvironmentTopologyPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "project-replay-sandbox",
                List.of(new ReplayEnvironmentTopologyComponentPlan(
                        "backend",
                        "SPRING_BOOT_BACKEND",
                        "replay-fizzms-10228-backend",
                        "DCE/backend",
                        "test2",
                        "bss-backend",
                        "project-test2-values.yaml",
                        "image/backend",
                        "REPLAY_COPY",
                        "PLAN_READY",
                        List.of(),
                        List.of("DRY_RUN_ONLY")
                )),
                List.of(),
                List.of("DRY_RUN_ONLY", "HUMAN_APPROVAL_REQUIRED"),
                List.of(),
                true,
                true,
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
