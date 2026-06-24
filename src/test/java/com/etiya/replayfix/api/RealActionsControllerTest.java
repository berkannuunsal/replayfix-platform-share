package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.BitbucketBranchFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrResponse;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskRequest;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.RealActionsSummaryResponse;
import com.etiya.replayfix.service.BitbucketBranchFlowService;
import com.etiya.replayfix.service.BitbucketPullRequestRealActionService;
import com.etiya.replayfix.service.BitbucketTest2DemoPrService;
import com.etiya.replayfix.service.BitbucketWorkspacePushService;
import com.etiya.replayfix.service.JiraRealActionService;
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
        when(summaryService.summary(caseId)).thenReturn(summary(caseId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RealActionsController(
                        jiraService,
                        branchService,
                        prService,
                        workspacePushService,
                        test2DemoPrService,
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
                        .value("[DRAFT] ReplayFix FIZZMS-10228"));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/workspace-push/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bugfixBranch")
                        .value("bugfix/FIZZMS-10228"))
                .andExpect(jsonPath("$.previewOnly").value(true));

        mockMvc.perform(post(
                        "/api/v1/cases/{caseId}/bitbucket/test2-demo-pr/preview",
                        caseId
                ).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationBranch")
                        .value("integration/test2/FIZZMS-10228"))
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
                "ReplayFix Test Execution - FIZZMS-10228",
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
                "[DRAFT] ReplayFix FIZZMS-10228",
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
                "ControllerBackend/src/test/java/com/company/replayfix/generated/FIZZMS10228ReplayFixDemoRegressionTest.java",
                "",
                "",
                "",
                "[DRAFT] ReplayFix FIZZMS-10228 demo regression test",
                List.of(),
                List.of(),
                List.of(),
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
