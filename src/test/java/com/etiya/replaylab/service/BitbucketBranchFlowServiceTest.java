package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.BitbucketBranchFlowRequest;
import com.etiya.replaylab.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replaylab.model.IntegrationModels.BitbucketMergeResult;
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

class BitbucketBranchFlowServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private BitbucketClient bitbucketClient;
    private ReplayLabProperties properties;
    private BitbucketBranchFlowService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketClient = mock(BitbucketClient.class);
        properties = new ReplayLabProperties();
        service = new BitbucketBranchFlowService(
                caseRepository,
                evidenceRepository,
                bitbucketClient,
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void branchFlowPreviewDoesNotCreateBranchOrMerge() {
        BitbucketBranchFlowResponse response = service.preview(caseId, request(false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.bugfixBranch()).isEqualTo("bugfix/FIZZMS-10228");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
        verify(bitbucketClient, never()).mergeBranches(any(), any(), any(), any());
    }

    @Test
    void branchExecuteRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.execute(caseId, request(false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_CREATE_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
    }

    @Test
    void branchExecuteDisabledReturnsRealActionsDisabled() {
        assertThatThrownBy(() -> service.execute(caseId, request(true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void branchExecuteCreatesBugfixAndIntegrationBranchesWhenMissing() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/FIZZMS-10228", false);
        mockBranchExists("integration/test2/FIZZMS-10228", false);
        when(bitbucketClient.createBranch(any(), any(), any(), any()))
                .thenReturn(new BitbucketBranchCreateResult(
                        true,
                        false,
                        "branch",
                        List.of()
                ));

        BitbucketBranchFlowResponse response =
                service.execute(caseId, request(true, true));

        assertThat(response.bugfixBranchCreated()).isTrue();
        assertThat(response.integrationBranchCreated()).isTrue();
        assertThat(response.blockers()).contains("BITBUCKET_MERGE_DISABLED");
        verify(bitbucketClient).createBranch(
                "DCE",
                "backend",
                "bugfix/FIZZMS-10228",
                "master"
        );
        verify(bitbucketClient).createBranch(
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2"
        );
    }

    @Test
    void branchExecuteStopsOnMergeConflict() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketMergeEnabled(true);
        mockBranchExists("master", true);
        mockBranchExists("test2", true);
        mockBranchExists("bugfix/FIZZMS-10228", true);
        mockBranchExists("integration/test2/FIZZMS-10228", true);
        when(bitbucketClient.mergeBranches(any(), any(), any(), any()))
                .thenReturn(new BitbucketMergeResult(
                        true,
                        false,
                        true,
                        List.of("conflict")
                ));

        BitbucketBranchFlowResponse response =
                service.execute(caseId, request(true, true));

        assertThat(response.mergeConflict()).isTrue();
        assertThat(response.blockers())
                .contains("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
    }

    @Test
    void branchExecuteDoesNotPushToProtectedBranchDirectly() {
        BitbucketBranchFlowRequest unsafe = new BitbucketBranchFlowRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "master",
                "test2",
                "test2",
                "integration/test2/FIZZMS-10228",
                true,
                true,
                false,
                false,
                false,
                false
        );

        BitbucketBranchFlowResponse response = service.preview(caseId, unsafe);

        assertThat(response.blockers()).contains("PROTECTED_BRANCH_WRITE_BLOCKED");
    }

    private void mockBranchExists(String branch, boolean exists) {
        when(bitbucketClient.branchExists("DCE", "backend", branch))
                .thenReturn(new BitbucketBranchCheckResult(
                        exists,
                        branch,
                        List.of()
                ));
    }

    private BitbucketBranchFlowRequest request(boolean confirm, boolean guardrails) {
        return new BitbucketBranchFlowRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                true,
                true,
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
