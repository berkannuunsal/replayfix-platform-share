package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.ApprovedWritePlanFile;
import com.etiya.replaylab.api.dto.ApprovedWritePlanResponse;
import com.etiya.replaylab.api.dto.ApprovedWritePlanTest;
import com.etiya.replaylab.service.ApprovedWritePlanService;
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

class ApprovedWritePlanControllerTest {

    @Test
    void returnsApprovedWritePlanForCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        ApprovedWritePlanService service = mock(ApprovedWritePlanService.class);
        when(service.plan(
                eq(caseId),
                eq(null),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ApprovedWritePlanController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/approved-write-plan",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.writePlanStatus")
                        .value("BLOCKED_BY_MISSING_APPROVAL"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.approvalRequiredBeforeWrite").value(true))
                .andExpect(jsonPath("$.proposedBranchName")
                        .value("bugfix/FIZZMS-10228-replaylab"))
                .andExpect(jsonPath("$.plannedFiles[0].fileType")
                        .value("REGRESSION_TEST"))
                .andExpect(jsonPath("$.plannedFiles[1].fileType")
                        .value("SOURCE_FIX"))
                .andExpect(jsonPath("$.guardrails[0]")
                        .value("NO_DIRECT_TEST2_COMMIT"));
    }

    @Test
    void passesQueryParametersToService() throws Exception {
        UUID caseId = UUID.randomUUID();
        ApprovedWritePlanService service = mock(ApprovedWritePlanService.class);
        when(service.plan(
                eq(caseId),
                eq("patch-1"),
                eq(false),
                eq(false),
                eq(false)
        )).thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ApprovedWritePlanController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/approved-write-plan",
                        caseId
                )
                        .param("patchPlanId", "patch-1")
                        .param("includeTestDraft", "false")
                        .param("includeFixDraft", "false")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));
    }

    private ApprovedWritePlanResponse response(UUID caseId) {
        String workspace = "work/" + caseId + "/repositories/backend";
        return new ApprovedWritePlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "BLOCKED_BY_MISSING_APPROVAL",
                true,
                true,
                true,
                "DCE/backend",
                "test2",
                "bugfix/FIZZMS-10228-replaylab",
                workspace,
                List.of(
                        new ApprovedWritePlanFile(
                                "REGRESSION_TEST",
                                workspace + "/src/test/java/Test.java",
                                "UserServiceImpl",
                                "updateUser",
                                "/user/region/update",
                                "",
                                "DRAFT",
                                false,
                                Map.of("scenario", "preferredProvince / region mismatch")
                        ),
                        new ApprovedWritePlanFile(
                                "SOURCE_FIX",
                                workspace + "/src/main/java/UserServiceImpl.java",
                                "UserServiceImpl",
                                "updateUser",
                                "/user/region/update",
                                "VALIDATION_GUARD",
                                "DRAFT",
                                false,
                                Map.of()
                        )
                ),
                List.of(new ApprovedWritePlanTest(
                        "API_INTEGRATION",
                        "FIZZMS10228UpdateUserRegressionTest",
                        "api_integration_covers_updateUser",
                        "preferredProvince / region mismatch",
                        "/user/region/update",
                        "DRAFT",
                        false,
                        Map.of()
                )),
                List.of("mvn clean compile -DskipTests"),
                List.of("REPLAY_REPRODUCTION"),
                List.of("NO_DIRECT_TEST2_COMMIT", "WORKSPACE_ONLY_WRITE"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
