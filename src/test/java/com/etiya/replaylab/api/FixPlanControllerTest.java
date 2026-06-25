package com.etiya.replaylab.api;

import com.etiya.replaylab.model.FixPlanCandidate;
import com.etiya.replaylab.model.FixPlanResponse;
import com.etiya.replaylab.service.CompanySourceReasoningService;
import com.etiya.replaylab.service.FixPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FixPlanControllerTest {

    @Test
    void endpointReturnsHttp200() throws Exception {
        UUID caseId = UUID.randomUUID();
        FixPlanService service = mock(FixPlanService.class);
        when(service.plan(eq(caseId), anyBoolean(), anyInt()))
                .thenReturn(response(caseId, List.of()));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FixPlanController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/fix-plan",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.fixCandidates[0].targetLayer")
                        .value("SERVICE_IMPL"));
    }

    @Test
    void endpointDoesNotFailWhenCompanyLlmIsUnavailable() throws Exception {
        UUID caseId = UUID.randomUUID();
        FixPlanService service = mock(FixPlanService.class);
        when(service.plan(eq(caseId), eq(true), anyInt()))
                .thenReturn(response(
                        caseId,
                        List.of(CompanySourceReasoningService
                                .COMPANY_LLM_UNAVAILABLE)
                ));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FixPlanController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/fix-plan",
                        caseId
                ).param("useCompanyLlm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.warnings[0]")
                        .value(CompanySourceReasoningService
                                .COMPANY_LLM_UNAVAILABLE))
                .andExpect(content().string(not(containsString("diff --git"))));
    }

    private FixPlanResponse response(UUID caseId, List<String> warnings) {
        FixPlanCandidate candidate = new FixPlanCandidate(
                "VALIDATION_GUARD",
                "CrmBackend/src/main/java/UserServiceImpl.java",
                "UserServiceImpl",
                "updateUser",
                "SERVICE_IMPL",
                "/user/region/update",
                List.of("/user/region/update"),
                "VALIDATION_GUARD",
                "Validation guard",
                "Plan only; no patch is generated.",
                "MEDIUM",
                0.4,
                "HYPOTHESIS",
                true,
                List.of(),
                List.of()
        );
        return new FixPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                0.4,
                List.of(candidate),
                candidate,
                List.of(),
                List.of(FixPlanService.APPLICATION_DB_EVIDENCE),
                true,
                true,
                warnings
        );
    }
}
