package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.TestExecutionPlanResponse;
import com.etiya.replaylab.service.TestExecutionPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestExecutionPlanControllerTest {

    @Test
    void returnsTestExecutionPlanForCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        TestExecutionPlanService service = mock(TestExecutionPlanService.class);
        when(service.plan(eq(caseId), eq(true), eq(true), eq(true)))
                .thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExecutionPlanController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/test-execution-plan",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.status").value("HYPOTHESIS"))
                .andExpect(jsonPath("$.executionPlanStatus")
                        .value("BLOCKED_BY_MISSING_APPROVAL"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.shouldRunTests").value(false))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.testTargets[0]")
                        .value("FIZZMS10228UpdateUserRegressionTest"));
    }

    @Test
    void passesQueryParametersToService() throws Exception {
        UUID caseId = UUID.randomUUID();
        TestExecutionPlanService service = mock(TestExecutionPlanService.class);
        when(service.plan(eq(caseId), eq(false), eq(false), eq(false)))
                .thenReturn(response(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExecutionPlanController(service))
                .build();

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/test-execution-plan",
                        caseId
                )
                        .param("includeWorkspaceDrafts", "false")
                        .param("includeReplayReadiness", "false")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));
    }

    private TestExecutionPlanResponse response(UUID caseId) {
        return new TestExecutionPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "BLOCKED_BY_MISSING_APPROVAL",
                true,
                true,
                false,
                "work/" + caseId + "/repositories/backend",
                List.of(
                        "mvn clean compile -DskipTests",
                        "mvn test -Dtest=FIZZMS10228UpdateUserRegressionTest"
                ),
                List.of("FIZZMS10228UpdateUserRegressionTest"),
                List.of("local", "test2-replay"),
                List.of("WireMock replay mocks"),
                List.of("USER_REGION_STATE"),
                List.of("HUMAN_APPROVAL_REQUIRED"),
                List.of("NO_AUTO_TEST_EXECUTION"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
