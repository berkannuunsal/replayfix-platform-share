package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.BitbucketBranchFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrResponse;
import com.etiya.replayfix.api.dto.BitbucketDefectPrFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.BitbucketSingleFileDefectPrFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrResponse;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replayfix.api.dto.DefectPrTargetedChangeResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskRequest;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.JenkinsValidationResponse;
import com.etiya.replayfix.api.dto.PrOutcomeSummaryResponse;
import com.etiya.replayfix.api.dto.PrRuleReviewResponse;
import com.etiya.replayfix.api.dto.RealActionsSummaryResponse;
import com.etiya.replayfix.service.BitbucketBackendDemoPrService;
import com.etiya.replayfix.service.BitbucketBranchFlowService;
import com.etiya.replayfix.service.BitbucketDefectPrFlowService;
import com.etiya.replayfix.service.BitbucketPullRequestRealActionService;
import com.etiya.replayfix.service.BitbucketSingleFileDefectPrFlowService;
import com.etiya.replayfix.service.BitbucketTest2DemoPrService;
import com.etiya.replayfix.service.BitbucketWorkspacePushService;
import com.etiya.replayfix.service.BitbucketPrCommentService;
import com.etiya.replayfix.service.DefectPrTargetedChangeService;
import com.etiya.replayfix.service.JenkinsValidationService;
import com.etiya.replayfix.service.JiraRealActionService;
import com.etiya.replayfix.service.PrRuleReviewService;
import com.etiya.replayfix.service.RealActionsSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealActionsControllerTest {

    @Test
    void routesJiraBitbucketAndSummaryEndpoints() throws Exception {
        UUID caseId = UUID.randomUUID();
        JiraRealActionService jiraService = mock(JiraRealActionService.class);
        BitbucketBranchFlowService branchService =
                mock(BitbucketBranchFlowService.class);
        BitbucketPullRequestRealActionService prService =
                mock(BitbucketPullRequestRealActionService.class);
        BitbucketWorkspacePushService workspacePushService =
                mock(BitbucketWorkspacePushService.class);
        BitbucketTest2DemoPrService test2DemoPrService =
                mock(BitbucketTest2DemoPrService.class);
        BitbucketBackendDemoPrService backendDemoPrService =
                mock(BitbucketBackendDemoPrService.class);
        BitbucketDefectPrFlowService defectPrFlowService =
                mock(BitbucketDefectPrFlowService.class);
        BitbucketSingleFileDefectPrFlowService singleFileDefectPrFlowService =
                mock(BitbucketSingleFileDefectPrFlowService.class);
        DefectPrTargetedChangeService targetedChangeService =
                mock(DefectPrTargetedChangeService.class);
        PrRuleReviewService prRuleReviewService =
                mock(PrRuleReviewService.class);
        BitbucketPrCommentService bitbucketPrCommentService =
                mock(BitbucketPrCommentService.class);
        JenkinsValidationService jenkinsValidationService =
                mock(JenkinsValidationService.class);
        RealActionsSummaryService summaryService =
                mock(RealActionsSummaryService.class);
        when(jiraService.preview(eq(caseId), any()))
                .thenReturn(jiraResponse(caseId));
        when(branchService.preview(eq(caseId), any()))
                .thenReturn(branchResponse(caseId));
        when(prService.preview(eq(caseId), any()))
                .thenReturn(prResponse(caseId));
        when(workspacePushService.preview(eq(caseId), any()))
                .thenReturn(workspacePushResponse(caseId));
        when(test2DemoPrService.preview(eq(caseId), any()))
                .thenReturn(test2DemoPrResponse(caseId));
        when(backendDemoPrService.preview(eq(caseId), any()))
                .thenReturn(backendDemoPrResponse(caseId));
        when(defectPrFlowService.preview(eq(caseId), any()))
                .thenReturn(defectPrFlowResponse(caseId));
        when(singleFileDefectPrFlowService.preview(eq(caseId), any()))
                .thenReturn(singleFileDefectPrFlowResponse(caseId));
        when(targetedChangeService.preview(eq(caseId), any()))
                .thenReturn(targetedChangeResponse(caseId));
        when(prRuleReviewService.preview(eq(caseId), any()))
                .thenReturn(prRuleReviewResponse(caseId));
        when(bitbucketPrCommentService.preview(eq(caseId), any()))
                .thenReturn(prOutcomeSummaryResponse(caseId));
        when(jenkinsValidationService.preview(eq(caseId), any()))
                .thenReturn(jenkinsValidationResponse(caseId));
        when(summaryService.summary(caseId)).thenReturn(summary(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RealActionsController(
                        jiraService,
                        branchService,
                        prService,
                        workspacePushService,
                        test2DemoPrService,
                        backendDemoPrService,
                        defectPrFlowService,
                        singleFileDefectPrFlowService,
                        targetedChangeService,
                        prRuleReviewService,
                        bitbucketPrCommentService,
                        jenkinsValidationService,
                        summaryService
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/jira/test-task/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewOnly").value(true))
                .andExpect(jsonPath("$.created").value(false));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/branch-flow/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bugfixBranch")
                        .value("bugfix/FIZZMS-10228"));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/pull-request/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title")
                        .value("[DRAFT] ReplayLab FIZZMS-10228"));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/workspace-push/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bugfixBranch")
                        .value("bugfix/FIZZMS-10228"))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/defect-pr-flow/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bugfixBranch")
                        .value("bugfix/FIZZMS-10228"))
                .andExpect(jsonPath("$.integrationBranch")
                        .value("Integration/test2/FIZZMS-10228"))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/defect-pr-flow/targeted-change/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filePath")
                        .value("ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java"))
                .andExpect(jsonPath("$.appliedRegressionTest").value(true))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/pr-rule-review/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("ACCEPT"))
                .andExpect(jsonPath("$.repositoryType").value("BACKEND"));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/pr-outcome-summary/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryPreview").value(org.hamcrest.Matchers.containsString("ReplayLab Summary")))
                .andExpect(jsonPath("$.prCommentCreated").value(false));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/jenkins/validation/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jenkinsJobName")
                        .value("MODERNIZATION.BACKEND_BUILD_12"))
                .andExpect(jsonPath("$.previewOnly").value(true))
                .andExpect(jsonPath("$.triggered").value(false));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/defect-pr-flow/single-file/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filePath")
                        .value("ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java"))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/test2-demo-pr/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationBranch")
                        .value("integration/test2/FIZZMS-10228"))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/backend-demo-pr/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bugfixBranch")
                        .value("bugfix/project-10228"))
                .andExpect(jsonPath("$.integrationBranch")
                        .value("Integration/test2/project-10228"))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/real-actions/summary",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realActionsEnabled").value(false));
    }

    @Test
    void createEndpointPropagatesDisabledStatus() throws Exception {
        UUID caseId = UUID.randomUUID();
        JiraRealActionService jiraService = mock(JiraRealActionService.class);
        when(jiraService.create(eq(caseId), any(JiraTestTaskRequest.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "REAL_ACTIONS_DISABLED"
                ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RealActionsController(
                        jiraService,
                        mock(BitbucketBranchFlowService.class),
                        mock(BitbucketPullRequestRealActionService.class),
                        mock(BitbucketWorkspacePushService.class),
                        mock(BitbucketTest2DemoPrService.class),
                        mock(BitbucketBackendDemoPrService.class),
                        mock(BitbucketDefectPrFlowService.class),
                        mock(BitbucketSingleFileDefectPrFlowService.class),
                        mock(DefectPrTargetedChangeService.class),
                        mock(PrRuleReviewService.class),
                        mock(BitbucketPrCommentService.class),
                        mock(JenkinsValidationService.class),
                        mock(RealActionsSummaryService.class)
                ))
                .build();

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/jira/test-task/create",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }

    private JiraTestTaskResponse jiraResponse(UUID caseId) {
        return new JiraTestTaskResponse(
                caseId,
                "FIZZMS-10228",
                true,
                false,
                "",
                "",
                "FIZZMS-10228",
                "Task",
                "ReplayLab Test Execution - FIZZMS-10228",
                "safe",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketBranchFlowResponse branchResponse(UUID caseId) {
        return new BitbucketBranchFlowResponse(
                caseId,
                "FIZZMS-10228",
                true,
                false,
                "DCE",
                "backend",
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                false,
                false,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketPullRequestResponse prResponse(UUID caseId) {
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
                "[DRAFT] ReplayLab FIZZMS-10228",
                "safe",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketWorkspacePushResponse workspacePushResponse(UUID caseId) {
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
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketTest2DemoPrResponse test2DemoPrResponse(UUID caseId) {
        return new BitbucketTest2DemoPrResponse(
                caseId,
                "FIZZMS-10228",
                true,
                false,
                "DCE",
                "backend",
                "test2",
                "integration/test2/FIZZMS-10228",
                "ControllerBackend/src/test/java/com/company/replayfix/generated/FIZZMS10228RegressionTest.java",
                "",
                "",
                "",
                "[DRAFT] ReplayLab FIZZMS-10228 fix proposal",
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketBackendDemoPrResponse backendDemoPrResponse(UUID caseId) {
        return new BitbucketBackendDemoPrResponse(
                caseId,
                "project-10228",
                "Fix agent category access regression demo",
                false,
                true,
                "master",
                "test2",
                "bugfix/project-10228",
                "Integration/test2/project-10228",
                "ControllerBackend/src/test/java/com/company/replayfix/generated/project10228ReplayLabDemoRegressionTest.java",
                "project-10228: Fix agent category access regression demo",
                "",
                "",
                "",
                "",
                "[DRAFT] ReplayLab project-10228 fix proposal",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketDefectPrFlowResponse defectPrFlowResponse(UUID caseId) {
        return new BitbucketDefectPrFlowResponse(
                caseId,
                "FIZZMS-10228",
                "safe summary",
                false,
                true,
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "FIZZMS-10228: safe summary",
                "",
                "",
                "",
                "",
                "[DRAFT] ReplayLab FIZZMS-10228 fix proposal",
                false,
                true,
                false,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketSingleFileDefectPrFlowResponse singleFileDefectPrFlowResponse(UUID caseId) {
        return new BitbucketSingleFileDefectPrFlowResponse(
                caseId,
                "FIZZMS-10228",
                "safe summary",
                false,
                true,
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "APPROVED_REGRESSION_TEST",
                "FIZZMS-10228: safe summary",
                "",
                "",
                "",
                "",
                "[DRAFT] ReplayLab FIZZMS-10228 fix proposal",
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private DefectPrTargetedChangeResponse targetedChangeResponse(UUID caseId) {
        return new DefectPrTargetedChangeResponse(
                caseId,
                "FIZZMS-10228",
                "safe summary",
                false,
                true,
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "APPROVED_REGRESSION_TEST",
                "FIZZMS-10228: safe summary",
                "",
                "",
                "",
                "",
                "[DRAFT] ReplayLab FIZZMS-10228 fix proposal",
                false,
                true,
                false,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.now()
        );
    }

    private PrRuleReviewResponse prRuleReviewResponse(UUID caseId) {
        return new PrRuleReviewResponse(
                caseId,
                "FIZZMS-10228",
                "BACKEND",
                "ACCEPT",
                0,
                List.of(),
                "ReviewStatus: ACCEPT\nBlocker violations: 0\n\nViolations of Rules(with rule IDs)\n- None",
                List.of("AGENTS.md", ".agents/AGENTS-Maintainability.md"),
                List.of(),
                List.of(),
                List.of("Continue guarded draft PR creation.")
        );
    }

    private PrOutcomeSummaryResponse prOutcomeSummaryResponse(UUID caseId) {
        return new PrOutcomeSummaryResponse(
                caseId,
                "77",
                "https://bitbucket/pr/77",
                "## ReplayLab Summary\n\n### Defect",
                false,
                "",
                false,
                List.of(),
                List.of("PREVIEW_ONLY_NO_PR_COMMENT_CREATED"),
                List.of("Create the ReplayLab PR summary comment after guarded approval."),
                Instant.now()
        );
    }

    private JenkinsValidationResponse jenkinsValidationResponse(UUID caseId) {
        return new JenkinsValidationResponse(
                caseId,
                "FIZZMS-10228",
                true,
                false,
                "MODERNIZATION.BACKEND_BUILD_12",
                "",
                "",
                "Integration/test2/FIZZMS-10228",
                "Integration/test2/FIZZMS-6686",
                "11397",
                Map.of(
                        "BRANCH", "Integration/test2/FIZZMS-10228",
                        "PR_ID", "11397",
                        "DEFECT_KEY", "FIZZMS-10228",
                        "VALIDATION_MODE", "PR_BUILD",
                        "REPLAYLAB_CASE_ID", caseId.toString(),
                        "SKIP_DEPLOY", "true"
                ),
                List.of("HUMAN_APPROVAL_REQUIRED", "NO_AUTO_DEPLOY"),
                List.of(),
                List.of("PREVIEW_ONLY_NO_JENKINS_TRIGGER"),
                List.of("Review validation plan and trigger only after human approval."),
                Instant.now()
        );
    }

    private RealActionsSummaryResponse summary(UUID caseId) {
        return new RealActionsSummaryResponse(
                caseId,
                "FIZZMS-10228",
                false,
                new RealActionsSummaryResponse.JiraTestTask(
                        false,
                        "",
                        "",
                        "NOT_CREATED"
                ),
                new RealActionsSummaryResponse.BitbucketBranchFlow(
                        false,
                        "",
                        "",
                        false,
                        "NOT_EXECUTED"
                ),
                new RealActionsSummaryResponse.BitbucketPullRequest(
                        false,
                        "",
                        "",
                        "",
                        "NOT_CREATED"
                ),
                List.of("HUMAN_APPROVAL_REQUIRED"),
                List.of("REAL_ACTIONS_DISABLED"),
                List.of(),
                List.of()
        );
    }
}
