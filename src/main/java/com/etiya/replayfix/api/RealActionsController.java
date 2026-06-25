package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.BitbucketBranchFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrRequest;
import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrResponse;
import com.etiya.replayfix.api.dto.BitbucketDefectPrFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketDefectPrFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestRequest;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.BitbucketSingleFileDefectPrFlowRequest;
import com.etiya.replayfix.api.dto.BitbucketSingleFileDefectPrFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrRequest;
import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrResponse;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushRequest;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replayfix.api.dto.DefectPrTargetedChangeRequest;
import com.etiya.replayfix.api.dto.DefectPrTargetedChangeResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskRequest;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.PrRuleReviewRequest;
import com.etiya.replayfix.api.dto.PrRuleReviewResponse;
import com.etiya.replayfix.api.dto.RealActionsSummaryResponse;
import com.etiya.replayfix.service.BitbucketBackendDemoPrService;
import com.etiya.replayfix.service.BitbucketBranchFlowService;
import com.etiya.replayfix.service.BitbucketDefectPrFlowService;
import com.etiya.replayfix.service.BitbucketPullRequestRealActionService;
import com.etiya.replayfix.service.BitbucketSingleFileDefectPrFlowService;
import com.etiya.replayfix.service.BitbucketTest2DemoPrService;
import com.etiya.replayfix.service.BitbucketWorkspacePushService;
import com.etiya.replayfix.service.DefectPrTargetedChangeService;
import com.etiya.replayfix.service.JiraRealActionService;
import com.etiya.replayfix.service.PrRuleReviewService;
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
    private final BitbucketWorkspacePushService workspacePushService;
    private final BitbucketTest2DemoPrService test2DemoPrService;
    private final BitbucketBackendDemoPrService backendDemoPrService;
    private final BitbucketDefectPrFlowService defectPrFlowService;
    private final BitbucketSingleFileDefectPrFlowService singleFileDefectPrFlowService;
    private final DefectPrTargetedChangeService targetedChangeService;
    private final PrRuleReviewService prRuleReviewService;
    private final RealActionsSummaryService summaryService;

    public RealActionsController(
            JiraRealActionService jiraRealActionService,
            BitbucketBranchFlowService branchFlowService,
            BitbucketPullRequestRealActionService pullRequestService,
            BitbucketWorkspacePushService workspacePushService,
            BitbucketTest2DemoPrService test2DemoPrService,
            BitbucketBackendDemoPrService backendDemoPrService,
            BitbucketDefectPrFlowService defectPrFlowService,
            BitbucketSingleFileDefectPrFlowService singleFileDefectPrFlowService,
            DefectPrTargetedChangeService targetedChangeService,
            PrRuleReviewService prRuleReviewService,
            RealActionsSummaryService summaryService
    ) {
        this.jiraRealActionService = jiraRealActionService;
        this.branchFlowService = branchFlowService;
        this.pullRequestService = pullRequestService;
        this.workspacePushService = workspacePushService;
        this.test2DemoPrService = test2DemoPrService;
        this.backendDemoPrService = backendDemoPrService;
        this.defectPrFlowService = defectPrFlowService;
        this.singleFileDefectPrFlowService = singleFileDefectPrFlowService;
        this.targetedChangeService = targetedChangeService;
        this.prRuleReviewService = prRuleReviewService;
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

    @PostMapping("/cases/{caseId}/bitbucket/workspace-push/preview")
    public BitbucketWorkspacePushResponse workspacePushPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketWorkspacePushRequest request
    ) {
        return workspacePushService.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/workspace-push/execute")
    public BitbucketWorkspacePushResponse workspacePushExecute(
            @PathVariable UUID caseId,
            @RequestBody BitbucketWorkspacePushRequest request
    ) {
        return workspacePushService.execute(caseId, request);
    }

    /** @deprecated compatibility route; use defect-pr-flow/targeted-change for new remediation PRs. */
    @Deprecated
    @PostMapping("/cases/{caseId}/bitbucket/test2-demo-pr/preview")
    public BitbucketTest2DemoPrResponse test2DemoPrPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketTest2DemoPrRequest request
    ) {
        return test2DemoPrService.preview(caseId, request);
    }

    /** @deprecated compatibility route; use defect-pr-flow/targeted-change for new remediation PRs. */
    @Deprecated
    @PostMapping("/cases/{caseId}/bitbucket/test2-demo-pr/create")
    public BitbucketTest2DemoPrResponse test2DemoPrCreate(
            @PathVariable UUID caseId,
            @RequestBody BitbucketTest2DemoPrRequest request
    ) {
        return test2DemoPrService.create(caseId, request);
    }

    /** @deprecated compatibility route; use defect-pr-flow/targeted-change for new remediation PRs. */
    @Deprecated
    @PostMapping("/cases/{caseId}/bitbucket/backend-demo-pr/preview")
    public BitbucketBackendDemoPrResponse backendDemoPrPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketBackendDemoPrRequest request
    ) {
        return backendDemoPrService.preview(caseId, request);
    }

    /** @deprecated compatibility route; use defect-pr-flow/targeted-change for new remediation PRs. */
    @Deprecated
    @PostMapping("/cases/{caseId}/bitbucket/backend-demo-pr/create")
    public BitbucketBackendDemoPrResponse backendDemoPrCreate(
            @PathVariable UUID caseId,
            @RequestBody BitbucketBackendDemoPrRequest request
    ) {
        return backendDemoPrService.create(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/defect-pr-flow/preview")
    public BitbucketDefectPrFlowResponse defectPrFlowPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketDefectPrFlowRequest request
    ) {
        return defectPrFlowService.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/defect-pr-flow/create")
    public BitbucketDefectPrFlowResponse defectPrFlowCreate(
            @PathVariable UUID caseId,
            @RequestBody BitbucketDefectPrFlowRequest request
    ) {
        return defectPrFlowService.create(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/defect-pr-flow/targeted-change/preview")
    public DefectPrTargetedChangeResponse targetedChangePreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) DefectPrTargetedChangeRequest request
    ) {
        return targetedChangeService.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/bitbucket/defect-pr-flow/targeted-change/create")
    public DefectPrTargetedChangeResponse targetedChangeCreate(
            @PathVariable UUID caseId,
            @RequestBody DefectPrTargetedChangeRequest request
    ) {
        return targetedChangeService.create(caseId, request);
    }

    @PostMapping("/cases/{caseId}/pr-rule-review/preview")
    public PrRuleReviewResponse prRuleReviewPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) PrRuleReviewRequest request
    ) {
        return prRuleReviewService.preview(caseId, request);
    }

    /** @deprecated use /bitbucket/defect-pr-flow/targeted-change/preview */
    @Deprecated
    @PostMapping("/cases/{caseId}/bitbucket/defect-pr-flow/single-file/preview")
    public BitbucketSingleFileDefectPrFlowResponse singleFileDefectPrFlowPreview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) BitbucketSingleFileDefectPrFlowRequest request
    ) {
        return singleFileDefectPrFlowService.preview(caseId, request);
    }

    /** @deprecated use /bitbucket/defect-pr-flow/targeted-change/create */
    @Deprecated
    @PostMapping("/cases/{caseId}/bitbucket/defect-pr-flow/single-file/create")
    public BitbucketSingleFileDefectPrFlowResponse singleFileDefectPrFlowCreate(
            @PathVariable UUID caseId,
            @RequestBody BitbucketSingleFileDefectPrFlowRequest request
    ) {
        return singleFileDefectPrFlowService.create(caseId, request);
    }

    @GetMapping("/cases/{caseId}/real-actions/summary")
    public RealActionsSummaryResponse realActionsSummary(
            @PathVariable UUID caseId
    ) {
        return summaryService.summary(caseId);
    }
}
