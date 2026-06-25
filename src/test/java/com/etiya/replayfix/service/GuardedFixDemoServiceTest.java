package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ApprovedWritePlanFile;
import com.etiya.replayfix.api.dto.ApprovedWritePlanResponse;
import com.etiya.replayfix.api.dto.ApprovedWritePlanTest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushRequest;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replayfix.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replayfix.api.dto.GuardedFixDemoPreviewRequest;
import com.etiya.replayfix.api.dto.GuardedFixDemoPreviewResponse;
import com.etiya.replayfix.api.dto.GuardedFixDemoTestOnlyPrRequest;
import com.etiya.replayfix.api.dto.GuardedFixDemoTestOnlyPrResponse;
import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.api.dto.WorkspaceWriteResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.etiya.replayfix.model.RegressionTestDraftResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardedFixDemoServiceTest {

    @TempDir
    Path tempDir;

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private CodeChangeAdvisoryService advisoryService;
    private PatchPlanCandidateService patchPlanService;
    private RegressionTestDraftService regressionTestDraftService;
    private ApprovedWritePlanService approvedWritePlanService;
    private WorkspaceWriteService workspaceWriteService;
    private BitbucketWorkspacePushService workspacePushService;
    private BitbucketPullRequestRealActionService pullRequestService;
    private WorkspaceGitOperations gitOperations;
    private BitbucketClient bitbucketClient;
    private ReplayFixProperties properties;
    private GuardedFixDemoService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        advisoryService = mock(CodeChangeAdvisoryService.class);
        patchPlanService = mock(PatchPlanCandidateService.class);
        regressionTestDraftService = mock(RegressionTestDraftService.class);
        approvedWritePlanService = mock(ApprovedWritePlanService.class);
        workspaceWriteService = mock(WorkspaceWriteService.class);
        workspacePushService = mock(BitbucketWorkspacePushService.class);
        pullRequestService = mock(BitbucketPullRequestRealActionService.class);
        gitOperations = mock(WorkspaceGitOperations.class);
        bitbucketClient = mock(BitbucketClient.class);
        properties = new ReplayFixProperties();
        service = new GuardedFixDemoService(
                caseRepository,
                evidenceRepository,
                advisoryService,
                patchPlanService,
                regressionTestDraftService,
                approvedWritePlanService,
                workspaceWriteService,
                workspacePushService,
                pullRequestService,
                gitOperations,
                bitbucketClient,
                properties,
                new ObjectMapper().findAndRegisterModules(),
                tempDir
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(advisoryService.summary(caseId)).thenReturn(advisorySummary());
        when(patchPlanService.candidate(caseId, false, true, true))
                .thenReturn(patchPlan(false));
        when(regressionTestDraftService.draft(caseId, false, 3))
                .thenReturn(regressionDraft());
        when(approvedWritePlanService.plan(caseId, null, true, true, true))
                .thenReturn(writePlan());
        when(workspaceWriteService.preview(caseId, true, true, true))
                .thenReturn(workspaceWrite());
        when(workspacePushService.preview(eq(caseId), any(BitbucketWorkspacePushRequest.class)))
                .thenReturn(workspacePush(List.of()));
        when(pullRequestService.preview(eq(caseId), any(BitbucketPullRequestRequest.class)))
                .thenReturn(prPreview(List.of()));
    }

    @Test
    void previewAggregatesExistingPreviewStages() {
        GuardedFixDemoPreviewResponse response =
                service.preview(caseId, previewRequest());

        assertThat(response.stages())
                .extracting("name")
                .contains(
                        "CODE_ADVISORY",
                        "PATCH_PLAN",
                        "REGRESSION_TEST_DRAFT",
                        "APPROVED_WRITE_PLAN",
                        "WORKSPACE_WRITE",
                        "WORKSPACE_PUSH",
                        "BITBUCKET_PR"
                );
        assertThat(response.recommendedPath()).isEqualTo("TEST_ONLY_PR");
    }

    @Test
    void previewDoesNotCallWritePushOrCreateMethods() {
        service.preview(caseId, previewRequest());

        verify(workspaceWriteService, never()).apply(
                any(),
                any(),
                any(Boolean.class),
                any(Boolean.class),
                any(Boolean.class)
        );
        verify(workspacePushService, never()).execute(any(), any());
        verify(pullRequestService, never()).create(any(), any());
        verify(bitbucketClient, never()).createPullRequest(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void missingApprovedWorkspaceWriteBlocksPreview() {
        when(workspacePushService.preview(eq(caseId), any(BitbucketWorkspacePushRequest.class)))
                .thenReturn(workspacePush(List.of(
                        "WORKSPACE_WRITE_NOT_APPROVED_OR_REVIEWED",
                        "WORKSPACE_GIT_REPOSITORY_MISSING"
                )));

        GuardedFixDemoPreviewResponse response =
                service.preview(caseId, previewRequest());

        assertThat(response.demoStatus()).isEqualTo("BLOCKED");
        assertThat(response.overallBlockers())
                .contains(
                        "WORKSPACE_WRITE_NOT_APPROVED_OR_REVIEWED",
                        "WORKSPACE_GIT_REPOSITORY_MISSING"
                );
    }

    @Test
    void prMissingSourceBranchIsShownInPrStage() {
        when(pullRequestService.preview(eq(caseId), any(BitbucketPullRequestRequest.class)))
                .thenReturn(prPreview(List.of("BITBUCKET_SOURCE_BRANCH_NOT_FOUND")));

        GuardedFixDemoPreviewResponse response =
                service.preview(caseId, previewRequest());

        assertThat(response.overallBlockers())
                .contains("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
        assertThat(response.stages().stream()
                .filter(stage -> "BITBUCKET_PR".equals(stage.name()))
                .findFirst()
                .orElseThrow()
                .blockers())
                .contains("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
    }

    @Test
    void executeRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.executeTestOnlyPr(
                caseId,
                executeRequest(false, false)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_EXECUTE_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
    }

    @Test
    void executeDisabledReturnsRealActionsDisabled() {
        assertThatThrownBy(() -> service.executeTestOnlyPr(
                caseId,
                executeRequest(true, true)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void protectedBranchIsBlockedBeforePush() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
        GuardedFixDemoTestOnlyPrRequest request =
                new GuardedFixDemoTestOnlyPrRequest(
                        "berkan",
                        "FIZZMS-10228",
                        "DCE",
                        "backend",
                        "master",
                        "test2",
                        "master",
                        "integration/test2/FIZZMS-10228",
                        "test2",
                        "ReplayLab: FIZZMS-10228 demo regression test",
                        true,
                        true,
                        true
                );

        GuardedFixDemoTestOnlyPrResponse response =
                service.executeTestOnlyPr(caseId, request);

        assertThat(response.blockers()).contains("PROTECTED_BRANCH_PUSH_BLOCKED");
        verify(gitOperations, never()).pushApprovedChanges(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void executeWritesTestOnlyFilePushesAndCreatesDraftPr() throws Exception {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
        when(gitOperations.pushApprovedChanges(
                any(),
                eq("master"),
                eq("test2"),
                eq("bugfix/FIZZMS-10228"),
                eq("integration/test2/FIZZMS-10228"),
                eq("ReplayLab: FIZZMS-10228 demo regression test")
        )).thenReturn(new WorkspaceGitOperations.WorkspaceGitPushResult(
                true,
                true,
                true,
                true,
                false,
                "abc123",
                "",
                ""
        ));
        when(bitbucketClient.branchExists("DCE", "backend",
                "integration/test2/FIZZMS-10228"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "integration/test2/FIZZMS-10228",
                        List.of()
                ));
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "test2",
                        List.of()
                ));
        when(bitbucketClient.createPullRequest(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new PullRequestResult(
                "42",
                "https://bitbucket/pr/42",
                "[DRAFT] ReplayLab FIZZMS-10228 demo regression test"
        ));

        GuardedFixDemoTestOnlyPrResponse response =
                service.executeTestOnlyPr(caseId, executeRequest(true, true));

        assertThat(response.executed()).isTrue();
        assertThat(response.testOnly()).isTrue();
        assertThat(response.generatedFilePath())
                .contains("src/test/java")
                .doesNotContain("src/main/java");
        assertThat(response.commitSha()).isEqualTo("abc123");
        assertThat(response.pullRequestUrl()).isEqualTo("https://bitbucket/pr/42");
        assertThat(response.blockers()).isEmpty();
        Path generated = tempDir.resolve("work")
                .resolve(caseId.toString())
                .resolve("repositories")
                .resolve("backend")
                .resolve(response.generatedFilePath());
        assertThat(Files.exists(generated)).isTrue();
        assertThat(Files.readString(generated))
                .contains("FIZZMS-10228")
                .doesNotContain("password")
                .doesNotContain("token");
        verify(bitbucketClient).createPullRequest(
                eq("DCE"),
                eq("backend"),
                eq("integration/test2/FIZZMS-10228"),
                eq("test2"),
                eq("[DRAFT] ReplayLab FIZZMS-10228 demo regression test"),
                any(),
                eq(List.of())
        );
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    private CodeChangeAdvisoryEvaluationSummaryResponse advisorySummary() {
        return new CodeChangeAdvisoryEvaluationSummaryResponse(
                caseId,
                "FIZZMS-10228",
                "backend",
                3,
                null,
                null,
                null,
                null,
                0.7,
                1,
                0,
                0,
                "ADVISORY_READY",
                Instant.now()
        );
    }

    private PatchPlanCandidateResponse patchPlan(boolean writesCode) {
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
                List.of("src/main/java/UserServiceImpl.java"),
                List.of("UserServiceImpl#updateUser"),
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                "VALIDATION_GUARD",
                Map.of(
                        "writesCode", writesCode,
                        "pseudoPatch", writesCode ? "patch" : ""
                ),
                List.of(),
                List.of(Map.of("testType", "API_INTEGRATION")),
                List.of("USER_REGION_STATE"),
                Map.of(),
                List.of("REPLAY_REPRODUCTION"),
                List.of(),
                Instant.now()
        );
    }

    private RegressionTestDraftResponse regressionDraft() {
        return new RegressionTestDraftResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                List.of("API_INTEGRATION"),
                "API_INTEGRATION",
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                true,
                List.of()
        );
    }

    private ApprovedWritePlanResponse writePlan() {
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
                "bugfix/FIZZMS-10228-replayfix",
                "work/" + caseId + "/repositories/backend",
                List.of(new ApprovedWritePlanFile(
                        "REGRESSION_TEST",
                        "work/" + caseId + "/repositories/backend/src/test/java/Test.java",
                        "UserServiceImpl",
                        "updateUser",
                        "/user/region/update",
                        "",
                        "DRAFT",
                        false,
                        Map.of()
                )),
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
                List.of("HUMAN_APPROVAL_REQUIRED"),
                List.of(),
                Instant.now()
        );
    }

    private WorkspaceWriteResponse workspaceWrite() {
        return new WorkspaceWriteResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "PREVIEW_READY",
                true,
                List.of(),
                List.of(),
                "work/" + caseId + "/repositories/backend",
                true,
                false,
                List.of("WORKSPACE_ONLY_WRITE"),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketWorkspacePushResponse workspacePush(List<String> blockers) {
        return new BitbucketWorkspacePushResponse(
                caseId,
                "FIZZMS-10228",
                true,
                false,
                "DCE",
                "backend",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                "",
                false,
                false,
                false,
                false,
                false,
                blockers,
                List.of(),
                List.of("Resolve workspace push blockers before creating a draft PR."),
                Instant.now()
        );
    }

    private BitbucketPullRequestResponse prPreview(List<String> blockers) {
        return new BitbucketPullRequestResponse(
                caseId,
                "FIZZMS-10228",
                true,
                false,
                "",
                "",
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2",
                "[DRAFT] ReplayLab FIZZMS-10228 replay fix plan",
                "safe description",
                Map.of(),
                blockers,
                List.of(),
                List.of("Resolve PR blockers before creation."),
                Instant.now()
        );
    }

    private GuardedFixDemoPreviewRequest previewRequest() {
        return new GuardedFixDemoPreviewRequest(
                "berkan",
                "FIZZMS-10228",
                "DCE",
                "backend",
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                "test2",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true
        );
    }

    private GuardedFixDemoTestOnlyPrRequest executeRequest(
            boolean confirm,
            boolean guardrails
    ) {
        return new GuardedFixDemoTestOnlyPrRequest(
                "berkan",
                "FIZZMS-10228",
                "DCE",
                "backend",
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                "test2",
                "ReplayLab: FIZZMS-10228 demo regression test",
                true,
                confirm,
                guardrails
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("backend");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
