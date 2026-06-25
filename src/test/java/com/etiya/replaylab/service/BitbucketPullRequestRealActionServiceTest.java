package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketPullRequestRequest;
import com.etiya.replaylab.api.dto.BitbucketPullRequestResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCheckResult;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BitbucketPullRequestRealActionServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private BitbucketClient bitbucketClient;
    private ReplayLabProperties properties;
    private BitbucketPullRequestRealActionService service;
    private ReplayCaseEntity caseEntity;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketClient = mock(BitbucketClient.class);
        properties = new ReplayLabProperties();
        caseEntity = caseEntity();
        service = new BitbucketPullRequestRealActionService(
                caseRepository,
                evidenceRepository,
                bitbucketClient,
                mock(CodeChangeAdvisoryService.class),
                mock(PatchPlanCandidateService.class),
                mock(TestExecutionPlanService.class),
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
    }

    @Test
    void prPreviewDoesNotCallCreatePullRequest() {
        mockBranchExists("integration/test2/FIZZMS-10228", true);
        mockBranchExists("test2", true);

        BitbucketPullRequestResponse response =
                service.preview(caseId, request(false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.title()).contains("[DRAFT]", "ReplayLab");
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
    void prCreateRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.create(caseId, request(false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_CREATE_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
    }

    @Test
    void prCreateDisabledReturnsRealActionsDisabled() {
        assertThatThrownBy(() -> service.create(caseId, request(true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void prCreateBlocksWhenSourceBranchIsMissing() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
        mockBranchExists("integration/test2/FIZZMS-10228", false);
        mockBranchExists("test2", true);

        BitbucketPullRequestResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isFalse();
        assertThat(response.blockers())
                .contains("BITBUCKET_SOURCE_BRANCH_NOT_FOUND");
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
    void prCreateUsesIntegrationBranchAndStoresPrUrl() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
        caseEntity.setGeneratedBranch("integration/test2/FIZZMS-10228");
        mockBranchExists("integration/test2/FIZZMS-10228", true);
        mockBranchExists("test2", true);
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
                "[DRAFT] ReplayLab FIZZMS-10228"
        ));

        BitbucketPullRequestResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.sourceBranch())
                .isEqualTo("integration/test2/FIZZMS-10228");
        assertThat(response.pullRequestUrl())
                .isEqualTo("https://bitbucket/pr/42");
        assertThat(response.descriptionPreview())
                .doesNotContain("token")
                .doesNotContain("password");
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    private void mockBranchExists(String branch, boolean exists) {
        when(bitbucketClient.branchExists("DCE", "backend", branch))
                .thenReturn(new BitbucketBranchCheckResult(
                        exists,
                        branch,
                        List.of()
                ));
    }

    private BitbucketPullRequestRequest request(boolean confirm, boolean guardrails) {
        return new BitbucketPullRequestRequest(
                "berkan",
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2",
                "[DRAFT] ReplayLab",
                false,
                true,
                false,
                List.of(),
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
