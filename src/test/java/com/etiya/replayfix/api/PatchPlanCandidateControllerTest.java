package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.service.PatchPlanCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatchPlanCandidateControllerTest {

    @Test
    void returnsPatchPlanCandidateForCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        PatchPlanCandidateService service =
                mock(PatchPlanCandidateService.class);
        when(service.candidate(
                eq(caseId),
                eq(false),
                eq(true),
                eq(true)
        )).thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PatchPlanCandidateController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/patch-plan-candidate",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.patchPlanStatus").value("DRAFT"))
                .andExpect(jsonPath("$.shouldProceedToPatch").value(false))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.targetEndpoint").value("/user/region/update"))
                .andExpect(jsonPath("$.targetClass").value("UserServiceImpl"))
                .andExpect(jsonPath("$.targetMethod").value("updateUser"))
                .andExpect(jsonPath("$.missingEvidence[0]")
                        .value("REPLAY_REPRODUCTION"));
    }

    @Test
    void passesQueryParametersToService() throws Exception {
        UUID caseId = UUID.randomUUID();
        PatchPlanCandidateService service =
                mock(PatchPlanCandidateService.class);
        when(service.candidate(
                eq(caseId),
                eq(true),
                eq(false),
                eq(false)
        )).thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PatchPlanCandidateController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/patch-plan-candidate",
                        caseId
                )
                        .param("useCompanyLlm", "true")
                        .param("includeReplayReadiness", "false")
                        .param("includeRegressionDraft", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"));
    }

    private PatchPlanCandidateResponse response(UUID caseId) {
        return new PatchPlanCandidateResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "DRAFT",
                false,
                true,
                "DCE/backend",
                "test2",
                "bugfix/FIZZMS-10228-replayfix",
                List.of("ControllerBackend/src/main/java/company/UserServiceImpl.java"),
                List.of("UserServiceImpl#updateUser"),
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                "VALIDATION_GUARD",
                Map.of(
                        "methodName",
                        "updateUser",
                        "writesCode",
                        false
                ),
                List.of(),
                List.of(Map.of("testType", "API_INTEGRATION")),
                List.of("USER_PREFERRED_PROVINCE"),
                Map.of("readinessStatus", "BLOCKED"),
                List.of("REPLAY_REPRODUCTION"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
