package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrRequest;
import com.etiya.replayfix.api.dto.BitbucketBackendDemoPrResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
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

class BitbucketBackendDemoPrServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private BitbucketClient bitbucketClient;
    private BackendDemoPrGitOperations gitOperations;
    private ReplayFixProperties properties;
    private BitbucketBackendDemoPrService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketClient = mock(BitbucketClient.class);
        gitOperations = mock(BackendDemoPrGitOperations.class);
        properties = new ReplayFixProperties();
        BitbucketDefectPrFlowService defectPrFlowService =
                new BitbucketDefectPrFlowService(
                        caseRepository,
                        evidenceRepository,
                        bitbucketClient,
                        gitOperations,
                        properties,
                        new ObjectMapper().findAndRegisterModules()
                );
        service = new BitbucketBackendDemoPrService(defectPrFlowService);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void previewDoesNotCreateBranchWriteCommitPushMergeOrCreatePr() {
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/project-10228", false);
        mockBranchExists("Integration/test2/project-10228", false);

        BitbucketBackendDemoPrResponse response =
                service.preview(caseId, request(false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.created()).isFalse();
        assertThat(response.commitMessage())
                .isEqualTo("project-10228: Fix agent category access regression demo");
        assertThat(response.generatedFilePath())
                .contains("src/test/java")
                .doesNotContain("src/main/java");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
        verify(bitbucketClient, never()).createPullRequest(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(gitOperations, never()).commitPushAndPrepareIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void createRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.create(caseId, request(false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_CREATE_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
    }

    @Test
    void createDisabledReturnsRealActionsDisabled() {
        assertThatThrownBy(() -> service.create(caseId, request(true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void defectSummaryIsRequired() {
        enableRealActions();
        BitbucketBackendDemoPrRequest missingSummary =
                new BitbucketBackendDemoPrRequest(
                        "berkan",
                        "DCE",
                        "backend",
                        "project-10228",
                        "",
                        "master",
                        "test2",
                        "bugfix/project-10228",
                        "Integration/test2/project-10228",
                        "[DRAFT] ReplayLab",
                        true,
                        true,
                        true,
                        true
                );

        BitbucketBackendDemoPrResponse response =
                service.create(caseId, missingSummary);

        assertThat(response.blockers()).contains("DEFECT_SUMMARY_REQUIRED");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
    }

    @Test
    void sourceAndTargetBaseBranchesMustExist() {
        enableRealActions();
        mockBranchExists("master", false);
        mockBranchExists("test2", false);
        mockBranchExists("bugfix/project-10228", false);
        mockBranchExists("Integration/test2/project-10228", false);

        BitbucketBackendDemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.blockers())
                .contains("BITBUCKET_SOURCE_BRANCH_NOT_FOUND")
                .contains("BITBUCKET_TARGET_BRANCH_NOT_FOUND");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
    }

    @Test
    void createRunsBugfixToIntegrationAndCreatesDraftPr() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/project-10228", false);
        mockBranchExists("Integration/test2/project-10228", false);
        when(bitbucketClient.createBranch(any(), any(), any(), any()))
                .thenReturn(new BitbucketBranchCreateResult(
                        true,
                        false,
                        "branch",
                        List.of()
                ));
        when(gitOperations.commitPushAndPrepareIntegrationBranch(
                eq("DCE"),
                eq("backend"),
                eq("master"),
                eq("test2"),
                eq("bugfix/project-10228"),
                eq("Integration/test2/project-10228"),
                eq(false),
                eq(false),
                eq("ControllerBackend/src/test/java/com/company/replayfix/generated/project10228ReplayLabRegressionTest.java"),
                any(),
                eq("project-10228: Fix agent category access regression demo")
        )).thenReturn(new BackendDemoPrGitOperations.BackendDemoPrGitResult(
                true,
                true,
                true,
                false,
                "bugfix123",
                "integration456",
                "",
                ""
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
                "77",
                "https://bitbucket/pr/77",
                "[DRAFT] ReplayLab project-10228 fix proposal"
        ));

        BitbucketBackendDemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.bugfixCommitSha()).isEqualTo("bugfix123");
        assertThat(response.integrationCommitSha()).isEqualTo("integration456");
        assertThat(response.pullRequestUrl()).isEqualTo("https://bitbucket/pr/77");
        assertThat(response.title())
                .isEqualTo("[DRAFT] ReplayLab project-10228 fix proposal");
        verify(bitbucketClient).createBranch(
                "DCE",
                "backend",
                "bugfix/project-10228",
                "master"
        );
        verify(bitbucketClient).createBranch(
                "DCE",
                "backend",
                "Integration/test2/project-10228",
                "test2"
        );
        verify(bitbucketClient).createPullRequest(
                eq("DCE"),
                eq("backend"),
                eq("Integration/test2/project-10228"),
                eq("test2"),
                eq("[DRAFT] ReplayLab project-10228 fix proposal"),
                any(),
                eq(List.of())
        );
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    @Test
    void existingBranchesRequireReuseFlag() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/project-10228", true);
        mockBranchExists("Integration/test2/project-10228", true);
        BitbucketBackendDemoPrRequest noReuse =
                new BitbucketBackendDemoPrRequest(
                        "berkan",
                        "DCE",
                        "backend",
                        "project-10228",
                        "Fix agent category access regression demo",
                        "master",
                        "test2",
                        "bugfix/project-10228",
                        "Integration/test2/project-10228",
                        "[DRAFT] ReplayLab",
                        true,
                        false,
                        true,
                        true
                );

        BitbucketBackendDemoPrResponse response =
                service.create(caseId, noReuse);

        assertThat(response.blockers())
                .contains("BITBUCKET_BUGFIX_BRANCH_ALREADY_EXISTS")
                .contains("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        verify(gitOperations, never()).commitPushAndPrepareIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void mergeConflictRequiresHumanAndDoesNotCreatePr() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/project-10228", true);
        mockBranchExists("Integration/test2/project-10228", true);
        when(gitOperations.commitPushAndPrepareIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                any(),
                any()
        )).thenReturn(new BackendDemoPrGitOperations.BackendDemoPrGitResult(
                false,
                true,
                false,
                true,
                "bugfix123",
                "",
                "",
                "BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN"
        ));

        BitbucketBackendDemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isFalse();
        assertThat(response.blockers())
                .contains("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
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
    void protectedBranchPushIsBlocked() {
        BitbucketBackendDemoPrRequest unsafe =
                new BitbucketBackendDemoPrRequest(
                        "berkan",
                        "DCE",
                        "backend",
                        "project-10228",
                        "Fix agent category access regression demo",
                        "master",
                        "test2",
                        "master",
                        "Integration/test2/project-10228",
                        "[DRAFT] ReplayLab",
                        true,
                        true,
                        false,
                        false
                );

        BitbucketBackendDemoPrResponse response =
                service.preview(caseId, unsafe);

        assertThat(response.blockers())
                .contains("BITBUCKET_BUGFIX_BRANCH_NAME_INVALID")
                .contains("PROTECTED_BRANCH_WRITE_BLOCKED");
    }

    @Test
    void responseDoesNotExposeTokenOrSecret() {
        enableRealActions();
        properties.getIntegrations().getBitbucket().setToken("secret-token-value");
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/project-10228", true);
        mockBranchExists("Integration/test2/project-10228", true);
        when(gitOperations.commitPushAndPrepareIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                any(),
                any()
        )).thenReturn(new BackendDemoPrGitOperations.BackendDemoPrGitResult(
                false,
                false,
                false,
                false,
                "",
                "",
                "",
                "failure secret-token-value Authorization Cookie"
        ));

        BitbucketBackendDemoPrResponse response =
                service.create(caseId, request(true, true));

        String serialized = response.toString();
        assertThat(serialized)
                .doesNotContain("secret-token-value")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie");
    }

    private void enableRealActions() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
    }

    private void mockBranchExists(String branch, boolean exists) {
        when(bitbucketClient.branchExists("DCE", "backend", branch))
                .thenReturn(new BitbucketBranchCheckResult(
                        exists,
                        branch,
                        List.of()
                ));
    }

    private BitbucketBackendDemoPrRequest request(
            boolean confirm,
            boolean guardrails
    ) {
        return new BitbucketBackendDemoPrRequest(
                "berkan",
                "DCE",
                "backend",
                "project-10228",
                "Fix agent category access regression demo",
                "master",
                "test2",
                "bugfix/project-10228",
                "Integration/test2/project-10228",
                "[DRAFT] ReplayLab",
                true,
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
