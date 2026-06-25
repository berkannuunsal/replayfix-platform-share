package com.etiya.replaylab.api;

import com.etiya.replaylab.model.RegressionTestAssertion;
import com.etiya.replaylab.model.RegressionTestDataRequirement;
import com.etiya.replaylab.model.RegressionTestDbValidationRequirement;
import com.etiya.replaylab.model.RegressionTestDraftResponse;
import com.etiya.replaylab.model.RegressionTestMockRequirement;
import com.etiya.replaylab.model.RegressionTestScenario;
import com.etiya.replaylab.model.RegressionTestStep;
import com.etiya.replaylab.service.CompanySourceReasoningService;
import com.etiya.replaylab.service.RegressionTestDraftService;
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

class RegressionTestDraftControllerTest {

    @Test
    void endpointReturnsHttp200() throws Exception {
        UUID caseId = UUID.randomUUID();
        RegressionTestDraftService service =
                mock(RegressionTestDraftService.class);
        when(service.draft(eq(caseId), anyBoolean(), anyInt()))
                .thenReturn(response(caseId, List.of()));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RegressionTestDraftController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/regression-test-draft",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.selectedTestType")
                        .value("API_INTEGRATION"))
                .andExpect(jsonPath("$.targetEndpoint")
                        .value("/user/region/update"))
                .andExpect(jsonPath("$.targetClass")
                        .value("UserServiceImpl"))
                .andExpect(jsonPath("$.targetMethod").value("updateUser"))
                .andExpect(jsonPath("$.requiresDbEvidence").value(true))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.scenarios[0].name")
                        .value("Region update rejects or normalizes inconsistent preferred province"))
                .andExpect(jsonPath("$.dbValidationRequirements[0].templateId")
                        .value("USER_PREFERRED_PROVINCE"));
    }

    @Test
    void companyLlmUnavailableDoesNotBreakDeterministicDraft()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        RegressionTestDraftService service =
                mock(RegressionTestDraftService.class);
        when(service.draft(eq(caseId), eq(true), anyInt()))
                .thenReturn(response(
                        caseId,
                        List.of(CompanySourceReasoningService
                                .COMPANY_LLM_UNAVAILABLE)
                ));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RegressionTestDraftController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/regression-test-draft",
                        caseId
                ).param("useCompanyLlm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.warnings[0]")
                        .value(CompanySourceReasoningService
                                .COMPANY_LLM_UNAVAILABLE))
                .andExpect(content().string(not(containsString("reasoning_content"))))
                .andExpect(content().string(not(containsString("diff --git"))));
    }

    private RegressionTestDraftResponse response(
            UUID caseId,
            List<String> warnings
    ) {
        return new RegressionTestDraftResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                List.of("API_INTEGRATION", "SERVICE_UNIT"),
                "API_INTEGRATION",
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                List.of(new RegressionTestScenario(
                        "Region update rejects or normalizes inconsistent preferred province",
                        "API_INTEGRATION",
                        "/user/region/update",
                        "UserServiceImpl",
                        "updateUser",
                        List.of("User has an existing region/preferred province state."),
                        "Call /user/region/update with UpdateAplUserPrefPrvncRequest.",
                        "Application should safely handle invalid province-region combinations.",
                        List.of(new RegressionTestStep(
                                1,
                                "Submit request",
                                "UpdateAplUserPrefPrvncRequest",
                                "Observed behavior remains HYPOTHESIS."
                        )),
                        List.of(new RegressionTestAssertion(
                                "API",
                                "API response should not silently accept invalid state.",
                                "Invalid state is rejected or normalized."
                        )),
                        List.of("DRAFT_ONLY")
                )),
                List.of(new RegressionTestDataRequirement(
                        "userId or customerId",
                        "Affected user identifier.",
                        true,
                        "APPLICATION_DB_EVIDENCE"
                )),
                List.of(new RegressionTestMockRequirement(
                        "repository/DAO calls behind UserServiceImpl#updateUser",
                        "Mock persistence collaborators only if API integration is not possible.",
                        true
                )),
                List.of(new RegressionTestDbValidationRequirement(
                        "USER_PREFERRED_PROVINCE",
                        "User preferred province",
                        "Read-only validation.",
                        List.of("userId"),
                        List.of("USER_PROFILE"),
                        List.of("PREFERRED_PROVINCE")
                )),
                true,
                true,
                warnings
        );
    }
}
