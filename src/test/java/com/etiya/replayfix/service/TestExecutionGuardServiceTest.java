package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.TestExecutionApprovalRequest;
import com.etiya.replayfix.api.dto.TestExecutionGuardResponse;
import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestExecutionGuardServiceTest {

    private UUID caseId;
    private TestExecutionPlanService testExecutionPlanService;
    private TestExecutionGuardService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        testExecutionPlanService = mock(TestExecutionPlanService.class);
        service = new TestExecutionGuardService(testExecutionPlanService);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(testExecutionPlanService.plan(
                eq(caseId),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(plan());
    }

    @Test
    void previewReturnsCommandsButDoesNotExecute() {
        TestExecutionGuardResponse response = service.preview(caseId, true);

        assertThat(response.executionStatus()).isEqualTo("PREVIEW_READY");
        assertThat(response.commandExecuted()).isFalse();
        assertThat(response.testCommands())
                .contains("mvn test -Dtest=FIZZMS10228UpdateUserRegressionTest");
    }

    @Test
    void applyWithoutApprovalIsBlocked() {
        TestExecutionGuardResponse response =
                service.apply(caseId, null, false);

        assertThat(response.executionStatus())
                .isEqualTo("BLOCKED_BY_MISSING_APPROVAL");
        assertThat(response.commandExecuted()).isFalse();
        assertThat(response.approvalPresent()).isFalse();
    }

    @Test
    void applyWithApprovalAndDryRunTrueIsDryRunReady() {
        TestExecutionGuardResponse response =
                service.apply(caseId, approval(), true);

        assertThat(response.executionStatus()).isEqualTo("DRY_RUN_READY");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.approvalPresent()).isTrue();
        assertThat(response.commandExecuted()).isFalse();
    }

    @Test
    void applyWithApprovalAndDryRunFalseIsExecutionDisabled() {
        TestExecutionGuardResponse response =
                service.apply(caseId, approval(), false);

        assertThat(response.executionStatus())
                .isEqualTo("BLOCKED_BY_EXECUTION_NOT_ENABLED");
        assertThat(response.dryRun()).isFalse();
        assertThat(response.approvalPresent()).isTrue();
        assertThat(response.commandExecuted()).isFalse();
        assertThat(response.blockers()).contains("TEST_EXECUTION_NOT_ENABLED");
    }

    @Test
    void includesGuardrailsAndDoesNotExposeSensitiveValues() {
        TestExecutionGuardResponse response =
                service.apply(caseId, approval(), false);

        assertThat(response.guardrails()).contains(
                "NO_AUTO_TEST_EXECUTION",
                "HUMAN_APPROVAL_REQUIRED",
                "WORKSPACE_ONLY",
                "NO_JENKINS",
                "NO_PR",
                "NO_BRANCH",
                "NO_ARGOCD_SYNC",
                "EXECUTION_DISABLED"
        );
        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("rawProductionPayload");
    }

    @Test
    void onlyCallsPlanServiceAndNeverExecutesCommand() {
        service.apply(caseId, approval(), false);

        verify(testExecutionPlanService)
                .plan(eq(caseId), eq(true), eq(true), eq(true));
    }

    private TestExecutionApprovalRequest approval() {
        return new TestExecutionApprovalRequest(
                "unit-test",
                "approval-1",
                "No execution in this foundation.",
                true
        );
    }

    private TestExecutionPlanResponse plan() {
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
                List.of("local"),
                List.of("WireMock replay mocks"),
                List.of("USER_REGION_STATE"),
                List.of("HUMAN_APPROVAL_REQUIRED"),
                List.of("NO_AUTO_TEST_EXECUTION"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private String asJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
