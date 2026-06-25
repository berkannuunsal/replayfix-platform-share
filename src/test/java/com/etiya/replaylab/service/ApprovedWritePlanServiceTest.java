package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ApprovedWritePlanResponse;
import com.etiya.replaylab.api.dto.PatchPlanCandidateResponse;
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

class ApprovedWritePlanServiceTest {

    private UUID caseId;
    private PatchPlanCandidateService patchPlanCandidateService;
    private ApprovedWritePlanService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        patchPlanCandidateService = mock(PatchPlanCandidateService.class);
        service = new ApprovedWritePlanService(patchPlanCandidateService);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(patchPlanCandidateService.candidate(
                eq(caseId),
                eq(false),
                eq(true),
                eq(true)
        )).thenReturn(patchPlan());
    }

    @Test
    void returnsDryRunApprovalGatedPlan() {
        ApprovedWritePlanResponse response =
                service.plan(caseId, null, true, true, true);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.writePlanStatus())
                .isEqualTo("BLOCKED_BY_MISSING_APPROVAL");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.approvalRequiredBeforeWrite()).isTrue();
        assertThat(response.targetRepository()).isEqualTo("DCE/backend");
        assertThat(response.baseBranch()).isEqualTo("test2");
        assertThat(response.proposedBranchName())
                .isEqualTo("bugfix/FIZZMS-10228-replaylab");
        assertThat(response.workspacePath())
                .isEqualTo("work/" + caseId + "/repositories/backend");
    }

    @Test
    void includesRegressionTestAndSourceFixDraftsWithWriteDisabled() {
        ApprovedWritePlanResponse response =
                service.plan(caseId, null, true, true, true);

        assertThat(response.plannedFiles())
                .extracting("fileType")
                .contains("REGRESSION_TEST", "SOURCE_FIX");
        assertThat(response.plannedFiles())
                .allSatisfy(file -> assertThat(file.writeAllowed()).isFalse());
        assertThat(asJson(response))
                .contains("preferredProvince / region mismatch")
                .contains("UserServiceImpl")
                .contains("updateUser")
                .contains("VALIDATION_GUARD");
    }

    @Test
    void includesPlannedTestsAndValidationCommands() {
        ApprovedWritePlanResponse response =
                service.plan(caseId, null, true, true, true);

        assertThat(response.plannedTests())
                .extracting("testType")
                .contains("API_INTEGRATION", "SERVICE_UNIT");
        assertThat(asJson(response.plannedTests()))
                .contains("preferredProvince")
                .contains("region mismatch");
        assertThat(response.plannedValidationCommands())
                .contains("mvn clean compile -DskipTests");
        assertThat(response.plannedValidationCommands())
                .anySatisfy(command -> assertThat(command)
                        .startsWith("mvn test -Dtest="));
        assertThat(response.plannedValidationCommands())
                .anySatisfy(command -> assertThat(command)
                        .contains("Jenkins validation is not triggered"));
    }

    @Test
    void includesMissingEvidenceAndGuardrails() {
        ApprovedWritePlanResponse response =
                service.plan(caseId, null, true, true, true);

        assertThat(response.missingEvidence()).contains(
                PatchPlanCandidateService.REPLAY_REPRODUCTION,
                PatchPlanCandidateService.FAILING_REGRESSION_TEST,
                PatchPlanCandidateService.JENKINS_VALIDATION
        );
        assertThat(response.guardrails()).contains(
                "HUMAN_APPROVAL_REQUIRED",
                "WORKSPACE_ONLY_WRITE",
                "NO_AUTO_JENKINS",
                "NO_ARGOCD_SYNC",
                "DRY_RUN_ONLY"
        );
    }

    @Test
    void doesNotExposeRawReasoningContentOrSensitiveValues() {
        ApprovedWritePlanResponse response =
                service.plan(caseId, null, true, true, true);

        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("SECRET_REASONING")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("rawProductionPayload");
    }

    @Test
    void doesNotWriteFilesCreateBranchesOpenPrsOrRunJenkins() {
        service.plan(caseId, null, true, true, true);

        verify(patchPlanCandidateService)
                .candidate(eq(caseId), eq(false), eq(true), eq(true));
    }

    @Test
    void dryRunFalseIsStillReturnedAsDryRunWithWarning() {
        ApprovedWritePlanResponse response =
                service.plan(caseId, "patch-1", true, true, false);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.warnings()).contains(
                "PATCH_PLAN_ID_NOT_PERSISTED_YET",
                "REAL_WORKSPACE_WRITE_NOT_ENABLED_USE_DRY_RUN"
        );
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
                List.of("ControllerBackend/src/main/java/company/commerce/backend/service/UserServiceImpl.java"),
                List.of("UserServiceImpl#updateUser"),
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                "VALIDATION_GUARD",
                Map.of(
                        "file",
                        "ControllerBackend/src/main/java/company/commerce/backend/service/UserServiceImpl.java",
                        "methodName",
                        "updateUser",
                        "changeType",
                        "VALIDATION_GUARD",
                        "description",
                        "Validate region/preferred province consistency.",
                        "writesCode",
                        false,
                        "opensPullRequest",
                        false
                ),
                List.of(),
                List.of(
                        Map.of(
                                "testType",
                                "API_INTEGRATION",
                                "name",
                                "preferredProvince / region mismatch scenario",
                                "targetEndpoint",
                                "/user/region/update"
                        ),
                        Map.of(
                                "testType",
                                "SERVICE_UNIT",
                                "action",
                                "Validate preferredProvince / region mismatch"
                        )
                ),
                List.of(
                        "USER_PREFERRED_PROVINCE",
                        "USER_REGION_STATE",
                        "CUSTOMER_ADDRESS_REGION",
                        "TAX_INFO_STATE",
                        "TIMEZONE_STATE",
                        "BILLING_ACCOUNT_REGION"
                ),
                Map.of("readinessStatus", "BLOCKED"),
                List.of(
                        PatchPlanCandidateService.REPLAY_REPRODUCTION,
                        PatchPlanCandidateService.FAILING_REGRESSION_TEST,
                        PatchPlanCandidateService.APPLICATION_DB_EVIDENCE,
                        PatchPlanCandidateService.JENKINS_VALIDATION
                ),
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
