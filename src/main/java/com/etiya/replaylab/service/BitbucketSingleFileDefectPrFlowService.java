package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketSingleFileDefectPrFlowRequest;
import com.etiya.replaylab.api.dto.BitbucketSingleFileDefectPrFlowResponse;
import com.etiya.replaylab.api.dto.DefectPrTargetedChangeRequest;
import com.etiya.replaylab.api.dto.DefectPrTargetedChangeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * @deprecated Compatibility adapter for the original single-file route.
 * TODO remove after callers migrate to /bitbucket/defect-pr-flow/targeted-change/*.
 */
@Deprecated
@Service
public class BitbucketSingleFileDefectPrFlowService {

    private final DefectPrTargetedChangeService targetedChangeService;

    public BitbucketSingleFileDefectPrFlowService(
            DefectPrTargetedChangeService targetedChangeService
    ) {
        this.targetedChangeService = targetedChangeService;
    }

    @Transactional(readOnly = true)
    public BitbucketSingleFileDefectPrFlowResponse preview(
            UUID caseId,
            BitbucketSingleFileDefectPrFlowRequest request
    ) {
        return fromTargeted(targetedChangeService.preview(caseId, toTargeted(request)));
    }

    @Transactional
    public BitbucketSingleFileDefectPrFlowResponse create(
            UUID caseId,
            BitbucketSingleFileDefectPrFlowRequest request
    ) {
        return fromTargeted(targetedChangeService.create(caseId, toTargeted(request)));
    }

    private DefectPrTargetedChangeRequest toTargeted(
            BitbucketSingleFileDefectPrFlowRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new DefectPrTargetedChangeRequest(
                request.requestedBy(),
                request.projectKey(),
                request.repositorySlug(),
                request.defectKey(),
                request.defectSummary(),
                request.environment(),
                request.sourceBaseBranch(),
                request.targetBaseBranch(),
                request.bugfixBranch(),
                request.integrationBranch(),
                request.filePath(),
                legacyChangeMode(request.changeMode()),
                request.titlePrefix(),
                request.allowReuseExistingBranches(),
                request.confirmCreate(),
                request.guardrailsAccepted()
        );
    }

    private String legacyChangeMode(String value) {
        if (value == null || value.isBlank()) {
            return "APPROVED_REGRESSION_TEST";
        }
        if ("GENERATED_TEST_ONLY".equalsIgnoreCase(value)) {
            return "TARGETED_TEST_CHANGE";
        }
        return value;
    }

    private BitbucketSingleFileDefectPrFlowResponse fromTargeted(
            DefectPrTargetedChangeResponse response
    ) {
        return new BitbucketSingleFileDefectPrFlowResponse(
                response.caseId(),
                response.defectKey(),
                response.defectSummary(),
                response.created(),
                response.previewOnly(),
                response.sourceBaseBranch(),
                response.targetBaseBranch(),
                response.bugfixBranch(),
                response.integrationBranch(),
                response.filePath(),
                response.changeMode(),
                response.commitMessage(),
                response.bugfixCommitSha(),
                response.integrationCommitSha(),
                response.pullRequestId(),
                response.pullRequestUrl(),
                response.title(),
                response.blockers(),
                response.warnings(),
                response.nextActions(),
                response.generatedAt()
        );
    }
}
