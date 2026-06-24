package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.BitbucketBranchFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskRequest;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.RealActionsSummaryResponse;
import com.etiya.replayfix.service.BitbucketBranchFlowService;
import com.etiya.replayfix.service.BitbucketPullRequestRealActionService;
import com.etiya.replayfix.service.JiraRealActionService;
import com.etiya.replayfix.service.RealActionsSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class RealActionsController {

    private final JiraRealActionService jiraRealActionService;
    private final BitbucketBranchFlowService branchFlowService;
    private final BitbucketPullRequestRealActionService pullRequestService;
    private final RealActionsSummaryService summaryService;

    public RealActionsController(
            JiraRealActionService jiraRealActionService,
            BitbucketBranchFlowService branchFlowService,
            BitbucketPullRequestRealActionService pullRequestService,
            RealActionsSummaryService summaryService
    ) {
        this.jiraRealActionService = jiraRealActionService;
        this.branchFlowService = branchFlowService;
        this.pullRequestService = pullRequestService;
        this.summaryService = summaryService;
    }

    @PostMapping("/cases/{caseId}/jira/test-task/preview")
    public JiraTestTaskResponse jiraTestTaskPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) JiraTestTaskRequest request
    ) {
        return jiraRealActionService.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/jira/test-task/create")
    public JiraTestTaskResponse jiraTestTaskCreate(
            @PathVariable UUID caseId,
            @RequestBody JiraTestTaskRequest request
    ) {
        return jiraRealActionService.create(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/branch-flow/preview")
    public BitbucketBranchFlowResponse branchFlowPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketBranchFlowRequest request
    ) {
        return branchFlowService.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/branch-flow/execute")
    public BitbucketBranchFlowResponse branchFlowExecute(
            @PathVariable UUID caseId,
            @RequestBody BitbucketBranchFlowRequest request
    ) {
        return branchFlowService.execute(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/pull-request/preview")
    public BitbucketPullRequestResponse pullRequestPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketPullRequestRequest request
    ) {
        return pullRequestService.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/pull-request/create")
    public BitbucketPullRequestResponse pullRequestCreate(
            @PathVariable UUID caseId,
            @RequestBody BitbucketPullRequestRequest request
    ) {
        return pullRequestService.create(caseId, request);
    }

    @GetMapping("/cases/{caseId}/real-actions/summary")
    public RealActionsSummaryResponse realActionsSummary(
            @PathVariable UUID caseId
    ) {
        return summaryService.summary(caseId);
    }
}
