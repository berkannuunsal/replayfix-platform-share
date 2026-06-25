package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketBackendDemoPrRequest;
import com.etiya.replaylab.api.dto.BitbucketBackendDemoPrResponse;
import com.etiya.replaylab.api.dto.BitbucketDefectPrFlowResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BitbucketBackendDemoPrService {

    private final BitbucketDefectPrFlowService defectPrFlowService;

    public BitbucketBackendDemoPrService(BitbucketDefectPrFlowService defectPrFlowService) {
        this.defectPrFlowService = defectPrFlowService;
    }

    @Transactional(readOnly = true)
    public BitbucketBackendDemoPrResponse preview(
            UUID caseId,
            BitbucketBackendDemoPrRequest request
    ) {
        return fromDefectResponse(defectPrFlowService.preview(
                caseId,
                defectPrFlowService.fromBackendDemoRequest(request)
        ));
    }

    @Transactional
    public BitbucketBackendDemoPrResponse create(
            UUID caseId,
            BitbucketBackendDemoPrRequest request
    ) {
        return fromDefectResponse(defectPrFlowService.create(
                caseId,
                defectPrFlowService.fromBackendDemoRequest(request)
        ));
    }

    private BitbucketBackendDemoPrResponse fromDefectResponse(
            BitbucketDefectPrFlowResponse response
    ) {
        return new BitbucketBackendDemoPrResponse(
                response.caseId(),
                response.defectKey(),
                response.defectSummary(),
                response.created(),
                response.previewOnly(),
                response.sourceBaseBranch(),
                response.targetBaseBranch(),
                response.bugfixBranch(),
                response.integrationBranch(),
                generatedLegacyFilePath(response),
                response.commitMessage(),
                response.bugfixCommitSha(),
                response.integrationCommitSha(),
                response.pullRequestId(),
                response.pullRequestUrl(),
                response.title(),
                response.blockers(),
                response.warnings(),
                response.branchLookupDiagnostics(),
                response.nextActions(),
                response.generatedAt()
        );
    }

    private String generatedLegacyFilePath(BitbucketDefectPrFlowResponse response) {
        if (response.appliedRegressionTest()) {
            return "ControllerBackend/src/test/java/com/etiya/replaylab/generated/"
                    + compact(response.defectKey())
                    + "ReplayLabRegressionTest.java";
        }
        if (response.appliedConfigChange()) {
            return ".replaylab/approved-changes/" + response.defectKey() + "/CONFIG_CHANGE_PLAN.md";
        }
        if (response.appliedSourceFix()) {
            return ".replaylab/approved-changes/" + response.defectKey() + "/SOURCE_FIX_PLAN.md";
        }
        return "";
    }

    private String compact(String value) {
        return (value == null || value.isBlank() ? "case" : value)
                .replaceAll("[^A-Za-z0-9]", "");
    }
}
