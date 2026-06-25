package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replaylab.api.dto.GeneratedDraftReviewedFile;
import com.etiya.replaylab.api.dto.PatchPlanCandidateResponse;
import com.etiya.replaylab.api.dto.TestExecutionPlanResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestExecutionPlanServiceTest {

    private UUID caseId;
    private GeneratedDraftReviewService generatedDraftReviewService;
    private PatchPlanCandidateService patchPlanCandidateService;
    private TestExecutionPlanService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        generatedDraftReviewService = mock(GeneratedDraftReviewService.class);
        patchPlanCandidateService = mock(PatchPlanCandidateService.class);
        service = new TestExecutionPlanService(
                generatedDraftReviewService,
                patchPlanCandidateService
        );
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(patchPlanCandidateService.candidate(
                eq(caseId),
                eq(false),
                eq(true),
                eq(true)
        )).thenReturn(patchPlan());
        when(generatedDraftReviewService.review(eq(caseId), eq(false), eq(0)))
                .thenReturn(draftReview("READY_FOR_HUMAN_REVIEW"));
    }

    @Test
    void returnsDryRunPlanAndNeverAuthorizesTestExecution() {
        TestExecutionPlanResponse response =
                service.plan(caseId, true, true, true);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.executionPlanStatus())
                .isEqualTo("BLOCKED_BY_MISSING_APPROVAL");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.shouldRunTests()).isFalse();
    }

    @Test
    void includesFizzmsTestTargetAndCommands() {
        TestExecutionPlanResponse response =
                service.plan(caseId, true, true, true);

        assertThat(response.testTargets())
                .contains("FIZZMS10228UpdateUserRegressionTest");
        assertThat(response.testCommands()).contains(
                "mvn clean compile -DskipTests",
                "mvn test -Dtest=FIZZMS10228UpdateUserRegressionTest"
        );
    }

    @Test
    void includesExpectedBlockersGuardrailsAndEvidence() {
        TestExecutionPlanResponse response =
                service.plan(caseId, true, true, true);

        assertThat(response.blockers()).contains(
                "HUMAN_APPROVAL_REQUIRED",
                "REPLAY_REPRODUCTION_MISSING",
                "DB_EVIDENCE_MISSING",
                "JENKINS_VALIDATION_NOT_RUN"
        );
        assertThat(response.guardrails()).contains(
                "NO_AUTO_TEST_EXECUTION",
                "HUMAN_APPROVAL_REQUIRED",
                "WORKSPACE_ONLY",
                "NO_JENKINS",
                "NO_PR",
                "NO_BRANCH",
                "NO_ARGOCD_SYNC",
                "NO_SECRET_EXPOSURE"
        );
        assertThat(response.requiredDbEvidence()).contains("USER_REGION_STATE");
    }

    @Test
    void marksDraftReviewNotReadyAsBlocker() {
        when(generatedDraftReviewService.review(eq(caseId), eq(false), eq(0)))
                .thenReturn(draftReview("BLOCKED_BY_MISSING_TEST_DRAFT"));

        TestExecutionPlanResponse response =
                service.plan(caseId, true, true, true);

        assertThat(response.blockers())
                .contains("GENERATED_DRAFT_REVIEW_NOT_READY");
    }

    @Test
    void dryRunFalseIsStillReturnedAsDryRunWithWarning() {
        TestExecutionPlanResponse response =
                service.plan(caseId, true, true, false);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.warnings())
                .contains("TEST_EXECUTION_PLAN_FORCES_DRY_RUN");
    }

    @Test
    void doesNotExposeSensitiveValuesOrRawReasoning() {
        TestExecutionPlanResponse response =
                service.plan(caseId, true, true, true);

        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("rawProductionPayload");
    }

    @Test
    void doesNotRunTestsAndOnlyCallsPlanningServices() {
        service.plan(caseId, true, true, true);

        verify(patchPlanCandidateService)
                .candidate(eq(caseId), eq(false), eq(true), eq(true));
        verify(generatedDraftReviewService)
                .review(eq(caseId), eq(false), eq(0));
    }

    private PatchPlanCandidateResponse patchPlan() {
        return new PatchPlanCandidateResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "DRAFT",
                false,
                true,
                "DCE/backend",
                "test2",
                "bugfix/FIZZMS-10228-replaylab",
                List.of(),
                List.of("UserServiceImpl#updateUser"),
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                "VALIDATION_GUARD",
                Map.of(),
                List.of(),
                List.of(),
                List.of("USER_REGION_STATE"),
                Map.of(),
                List.of(
                        PatchPlanCandidateService.REPLAY_REPRODUCTION,
                        PatchPlanCandidateService.APPLICATION_DB_EVIDENCE,
                        PatchPlanCandidateService.JENKINS_VALIDATION
                ),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private GeneratedDraftReviewResponse draftReview(String reviewStatus) {
        return new GeneratedDraftReviewResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                reviewStatus,
                "work/" + caseId + "/repositories/backend",
                List.of(new GeneratedDraftReviewedFile(
                        "REGRESSION_TEST",
                        "src/test/java/com/company/commerce/backend/crm/user/FIZZMS10228UpdateUserRegressionTest.java",
                        true,
                        true,
                        100,
                        "",
                        List.of(),
                        List.of(),
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                false,
                false,
                false,
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
