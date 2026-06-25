package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketDefectPrFlowRequest;
import com.etiya.replaylab.api.dto.BitbucketDefectPrFlowResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replaylab.model.IntegrationModels.PullRequestResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BitbucketDefectPrFlowServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private BitbucketClient bitbucketClient;
    private BackendDemoPrGitOperations gitOperations;
    private ReplayLabProperties properties;
    private BitbucketDefectPrFlowService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketClient = mock(BitbucketClient.class);
        gitOperations = mock(BackendDemoPrGitOperations.class);
        properties = new ReplayLabProperties();
        properties.getIntegrations().getBitbucket().getBranchStrategy()
                .getEnvironmentTargets()
                .put("test2", "Integration/test2/FIZZMS-6686");
        service = new BitbucketDefectPrFlowService(
                caseRepository,
                evidenceRepository,
                bitbucketClient,
                gitOperations,
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void previewDefaultsBranchesFromDefectKeyAndEnvironmentTargetMapping() {
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", false);
        mockBranchExists("Integration/test2/CRM-123", false);

        BitbucketDefectPrFlowResponse response =
                service.preview(caseId, request("CRM-123", false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.bugfixBranch()).isEqualTo("bugfix/CRM-123");
        assertThat(response.integrationBranch())
                .isEqualTo("Integration/test2/CRM-123");
        assertThat(response.targetBaseBranch())
                .isEqualTo("Integration/test2/FIZZMS-6686");
        assertThat(response.blockers()).isEmpty();
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
        verify(gitOperations, never()).commitPushAndPrepareIntegrationBranch(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any());
    }

    @Test
    void targetBaseBranchIsRequiredWhenNoRequestOrMappingExists() {
        properties.getIntegrations().getBitbucket().getBranchStrategy()
                .getEnvironmentTargets()
                .clear();

        BitbucketDefectPrFlowResponse response =
                service.preview(caseId, request("CRM-123", false, false));

        assertThat(response.blockers())
                .contains("TARGET_BASE_BRANCH_REQUIRED");
        verify(bitbucketClient, never()).branchExists(any(), any(), any());
    }

    @Test
    void approvedChangeIsRequired() {
        BitbucketDefectPrFlowRequest request =
                new BitbucketDefectPrFlowRequest(
                        "berkan", "DCE", "backend", "CRM-123", "Safe summary",
                        "test2", "master", "Integration/test2/FIZZMS-6686",
                        "", "", "[DRAFT] ReplayLab", true,
                        false, false, false, false, false);

        BitbucketDefectPrFlowResponse response =
                service.preview(caseId, request);

        assertThat(response.blockers())
                .contains("APPROVED_CHANGE_REQUIRED");
    }

    @Test
    void commitMessageNormalizesDuplicateDefectKey() {
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/FIZZMS-10228", false);
        mockBranchExists("Integration/test2/FIZZMS-10228", false);
        BitbucketDefectPrFlowRequest request =
                new BitbucketDefectPrFlowRequest(
                        "berkan", "DCE", "backend", "FIZZMS-10228",
                        "FIZZMS-10228 : FMS-170772//Region mismatch",
                        "test2", "master", "Integration/test2/FIZZMS-6686",
                        "", "", "[DRAFT] ReplayLab", true,
                        false, true, false, false, false);

        BitbucketDefectPrFlowResponse response =
                service.preview(caseId, request);

        assertThat(response.commitMessage())
                .isEqualTo("FIZZMS-10228: FMS-170772//Region mismatch");
    }

    @Test
    void createRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.create(caseId, request("CRM-123", true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void createPushesBugfixAndIntegrationThenCreatesDraftPr() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", false);
        mockBranchExists("Integration/test2/CRM-123", false);
        when(bitbucketClient.createBranch(any(), any(), any(), any()))
                .thenReturn(new BitbucketBranchCreateResult(true, false, "branch", List.of()));
        when(gitOperations.commitPushAndPrepareIntegrationBranch(
                eq("DCE"), eq("backend"), eq("master"),
                eq("Integration/test2/FIZZMS-6686"), eq("bugfix/CRM-123"),
                eq("Integration/test2/CRM-123"), eq(false), eq(false),
                any(), any(), eq("CRM-123: Safe summary")
        )).thenReturn(new BackendDemoPrGitOperations.BackendDemoPrGitResult(
                true, true, true, false, "bugfix123", "integration456", "", ""));
        when(bitbucketClient.createPullRequest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PullRequestResult(
                        "88", "https://bitbucket/pr/88",
                        "[DRAFT] ReplayLab CRM-123 fix proposal"));

        BitbucketDefectPrFlowResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.pullRequestUrl()).isEqualTo("https://bitbucket/pr/88");
        assertThat(response.appliedRegressionTest()).isTrue();
        verify(bitbucketClient).createBranch("DCE", "backend", "bugfix/CRM-123", "master");
        verify(bitbucketClient).createBranch(
                "DCE", "backend", "Integration/test2/CRM-123",
                "Integration/test2/FIZZMS-6686");
        verify(bitbucketClient).createPullRequest(
                eq("DCE"), eq("backend"), eq("Integration/test2/CRM-123"),
                eq("Integration/test2/FIZZMS-6686"),
                eq("[DRAFT] ReplayLab CRM-123 fix proposal"), any(), eq(List.of()));
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    @Test
    void mergeConflictStopsBeforePrAndReturnsConflictedFiles() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", true);
        mockBranchExists("Integration/test2/CRM-123", true);
        when(gitOperations.commitPushAndPrepareIntegrationBranch(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any()
        )).thenReturn(new BackendDemoPrGitOperations.BackendDemoPrGitResult(
                false, true, false, true, "bugfix123", "",
                List.of("ControllerBackend/src/test/java/ConflictTest.java"),
                "", "BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN"));

        BitbucketDefectPrFlowResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.blockers())
                .contains("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
        assertThat(response.conflictedFiles())
                .contains("ControllerBackend/src/test/java/ConflictTest.java");
        verify(bitbucketClient, never()).createPullRequest(
                any(), any(), any(), any(), any(), any(), any());
    }

    private void enableRealActions() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
    }

    private void mockBranchExists(String branch, boolean exists) {
        when(bitbucketClient.branchExists("DCE", "backend", branch))
                .thenReturn(new BitbucketBranchCheckResult(exists, branch, List.of()));
    }

    private BitbucketDefectPrFlowRequest request(
            String defectKey,
            boolean confirm,
            boolean guardrails
    ) {
        return new BitbucketDefectPrFlowRequest(
                "berkan", "DCE", "backend", defectKey, "Safe summary",
                "test2", "master", "", "", "", "[DRAFT] ReplayLab",
                true, false, true, false, confirm, guardrails);
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
