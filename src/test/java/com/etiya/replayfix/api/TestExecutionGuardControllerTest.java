package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.TestExecutionGuardResponse;
import com.etiya.replayfix.service.TestExecutionGuardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TestExecutionGuardControllerTest {

    @Test
    void previewReturnsPreviewReady() throws Exception {
        UUID caseId = UUID.randomUUID();
        TestExecutionGuardService service = mock(TestExecutionGuardService.class);
        when(service.preview(eq(caseId), eq(true)))
                .thenReturn(response(caseId, "PREVIEW_READY", true, false));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExecutionGuardController(
                        service,
                        new ObjectMapper()
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/test-execution/preview",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionStatus")
                        .value("PREVIEW_READY"))
                .andExpect(jsonPath("$.commandExecuted").value(false));
    }

    @Test
    void applyWithoutApprovalReturnsConflict() throws Exception {
        UUID caseId = UUID.randomUUID();
        TestExecutionGuardService service = mock(TestExecutionGuardService.class);
        when(service.apply(eq(caseId), any(), eq(false)))
                .thenReturn(response(
                        caseId,
                        "BLOCKED_BY_MISSING_APPROVAL",
                        false,
                        false
                ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExecutionGuardController(
                        service,
                        new ObjectMapper()
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/test-execution/apply",
                        caseId
                )
                        .param("dryRun", "false"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.executionStatus")
                        .value("BLOCKED_BY_MISSING_APPROVAL"))
                .andExpect(jsonPath("$.commandExecuted").value(false));
    }

    @Test
    void applyWithApprovalAndDryRunFalseReturnsExecutionDisabled()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        TestExecutionGuardService service = mock(TestExecutionGuardService.class);
        when(service.apply(eq(caseId), any(), eq(false)))
                .thenReturn(response(
                        caseId,
                        "BLOCKED_BY_EXECUTION_NOT_ENABLED",
                        false,
                        true
                ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExecutionGuardController(
                        service,
                        new ObjectMapper()
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/test-execution/apply",
                        caseId
                )
                        .param("dryRun", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approvedBy": "controller-test",
                                  "approvalNote": "dry-run only",
                                  "acceptedGuardrails": true
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.executionStatus")
                        .value("BLOCKED_BY_EXECUTION_NOT_ENABLED"))
                .andExpect(jsonPath("$.commandExecuted").value(false));
    }

    private TestExecutionGuardResponse response(
            UUID caseId,
            String executionStatus,
            boolean dryRun,
            boolean approvalPresent
    ) {
        return new TestExecutionGuardResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                executionStatus,
                dryRun,
                true,
                approvalPresent,
                false,
                List.of("mvn test -Dtest=FIZZMS10228UpdateUserRegressionTest"),
                List.of("FIZZMS10228UpdateUserRegressionTest"),
                List.of("HUMAN_APPROVAL_REQUIRED"),
                List.of("NO_AUTO_TEST_EXECUTION"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
