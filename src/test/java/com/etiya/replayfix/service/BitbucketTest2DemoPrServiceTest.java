package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrRequest;
import com.etiya.replayfix.api.dto.BitbucketTest2DemoPrResponse;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BitbucketTest2DemoPrServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private BitbucketClient bitbucketClient;
    private Test2DemoPrGitOperations gitOperations;
    private ReplayFixProperties properties;
    private BitbucketTest2DemoPrService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketClient = mock(BitbucketClient.class);
        gitOperations = mock(Test2DemoPrGitOperations.class);
        properties = new ReplayFixProperties();
        service = new BitbucketTest2DemoPrService(
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
    void previewDoesNotCreateBranchWritePushOrCreatePr() {
        BitbucketTest2DemoPrResponse response =
                service.preview(caseId, request(false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.created()).isFalse();
        assertThat(response.integrationBranch())
                .isEqualTo("integration/test2/FIZZMS-10228");
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
        verify(gitOperations, never()).commitAndPushIntegrationBranch(
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
    void targetBranchLookupSupportsRefsHeadsTest2() {
        enableRealActions();
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "test2",
                        List.of()
                ));
        when(bitbucketClient.branchExists("DCE", "backend", "refs/heads/test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "refs/heads/test2",
                        List.of()
                ));
        when(bitbucketClient.branchExists("DCE", "backend",
                "integration/test2/FIZZMS-10228"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "integration/test2/FIZZMS-10228",
                        List.of()
                ));
        when(bitbucketClient.createBranch(any(), any(), any(), any()))
                .thenReturn(new BitbucketBranchCreateResult(
                        true,
                        false,
                        "integration/test2/FIZZMS-10228",
                        List.of()
                ));
        when(gitOperations.commitAndPushIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new Test2DemoPrGitOperations.Test2DemoPrGitResult(
                false,
                "",
                "",
                "stop-before-pr"
        ));

        BitbucketTest2DemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.blockers())
                .contains("BITBUCKET_TEST2_DEMO_PR_PUSH_FAILED");
        verify(bitbucketClient).createBranch(
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2"
        );
    }

    @Test
    void integrationBranchPrefixIsRequired() {
        BitbucketTest2DemoPrRequest unsafe =
                new BitbucketTest2DemoPrRequest(
                        "berkan",
                        "DCE",
                        "backend",
                        "FIZZMS-10228",
                        "test2",
                        "feature/FIZZMS-10228",
                        "[DRAFT] ReplayLab",
                        true,
                        true,
                        false,
                        false
                );

        BitbucketTest2DemoPrResponse response = service.preview(caseId, unsafe);

        assertThat(response.blockers())
                .contains("BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID");
    }

    @Test
    void protectedBranchDirectPushIsBlocked() {
        BitbucketTest2DemoPrRequest unsafe =
                new BitbucketTest2DemoPrRequest(
                        "berkan",
                        "DCE",
                        "backend",
                        "FIZZMS-10228",
                        "test2",
                        "test2",
                        "[DRAFT] ReplayLab",
                        true,
                        true,
                        false,
                        false
                );

        BitbucketTest2DemoPrResponse response = service.preview(caseId, unsafe);

        assertThat(response.blockers())
                .contains("PROTECTED_BRANCH_PUSH_BLOCKED");
    }

    @Test
    void createWritesTestOnlyChangePushesIntegrationBranchAndCreatesDraftPr() {
        enableRealActions();
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "test2",
                        List.of()
                ));
        when(bitbucketClient.branchExists("DCE", "backend",
                "integration/test2/FIZZMS-10228"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "integration/test2/FIZZMS-10228",
                        List.of()
                ));
        when(bitbucketClient.createBranch(
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2"
        )).thenReturn(new BitbucketBranchCreateResult(
                true,
                false,
                "integration/test2/FIZZMS-10228",
                List.of()
        ));
        when(gitOperations.commitAndPushIntegrationBranch(
                eq("DCE"),
                eq("backend"),
                eq("test2"),
                eq("integration/test2/FIZZMS-10228"),
                eq("ControllerBackend/src/test/java/com/company/replayfix/generated/FIZZMS10228ReplayLabDemoRegressionTest.java"),
                any(),
                eq("ReplayLab: FIZZMS-10228 demo regression test")
        )).thenReturn(new Test2DemoPrGitOperations.Test2DemoPrGitResult(
                true,
                "abc123",
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
                "42",
                "https://bitbucket/pr/42",
                "[DRAFT] ReplayLab FIZZMS-10228 demo regression test"
        ));

        BitbucketTest2DemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.commitSha()).isEqualTo("abc123");
        assertThat(response.pullRequestUrl()).isEqualTo("https://bitbucket/pr/42");
        assertThat(response.title())
                .isEqualTo("[DRAFT] ReplayLab FIZZMS-10228 demo regression test");
        assertThat(response.generatedFilePath())
                .contains("FIZZMS10228")
                .contains("src/test/java")
                .doesNotContain("src/main/java");
        verify(bitbucketClient).createPullRequest(
                eq("DCE"),
                eq("backend"),
                eq("integration/test2/FIZZMS-10228"),
                eq("test2"),
                eq("[DRAFT] ReplayLab FIZZMS-10228 demo regression test"),
                any(),
                eq(List.of())
        );
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    @Test
    void integrationBranchLookupFailureDoesNotBlockCreateAttempt() {
        enableRealActions();
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "test2",
                        List.of("BRANCH_LOOKUP_DIAGNOSTICS|requested=test2|normalized=test2|strategies=direct:test2,filterText:test2|httpStatuses=[404, 200]|matchedBranchId=refs/heads/test2|matchedDisplayId=test2")
                ));
        when(bitbucketClient.branchExists("DCE", "backend",
                "integration/test2/FIZZMS-10228"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "integration/test2/FIZZMS-10228",
                        List.of(
                                "BITBUCKET_BRANCH_LOOKUP_FAILED",
                                "BRANCH_LOOKUP_DIAGNOSTICS|requested=integration/test2/FIZZMS-10228|normalized=integration/test2/FIZZMS-10228|strategies=direct:integration/test2/FIZZMS-10228,direct:refs/heads/integration/test2/FIZZMS-10228,filterText:integration/test2/FIZZMS-10228|httpStatuses=[404, 0, 500]|matchedBranchId=|matchedDisplayId="
                        )
                ));
        when(bitbucketClient.createBranch(
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2"
        )).thenReturn(new BitbucketBranchCreateResult(
                true,
                false,
                "integration/test2/FIZZMS-10228",
                List.of()
        ));
        when(gitOperations.commitAndPushIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new Test2DemoPrGitOperations.Test2DemoPrGitResult(
                true,
                "abc123",
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
                "42",
                "https://bitbucket/pr/42",
                "[DRAFT] ReplayLab FIZZMS-10228 demo regression test"
        ));

        BitbucketTest2DemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.blockers())
                .doesNotContain("BITBUCKET_BRANCH_LOOKUP_FAILED");
        assertThat(response.warnings())
                .contains("BITBUCKET_INTEGRATION_BRANCH_LOOKUP_UNCERTAIN_CREATE_ATTEMPT_ALLOWED");
        verify(bitbucketClient).createBranch(
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2"
        );
    }

    @Test
    void targetBranchLookupFailureBlocksCreate() {
        enableRealActions();
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "test2",
                        List.of("BITBUCKET_BRANCH_LOOKUP_FAILED")
                ));
        when(bitbucketClient.branchExists("DCE", "backend", "refs/heads/test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "refs/heads/test2",
                        List.of("BITBUCKET_BRANCH_LOOKUP_FAILED")
                ));

        BitbucketTest2DemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isFalse();
        assertThat(response.blockers())
                .contains("BITBUCKET_BRANCH_LOOKUP_FAILED");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
        verify(gitOperations, never()).commitAndPushIntegrationBranch(
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
    void targetBranchTrulyAbsentReturnsTargetBranchNotFound() {
        enableRealActions();
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "test2",
                        List.of("BRANCH_LOOKUP_DIAGNOSTICS|requested=test2|normalized=test2|strategies=direct:test2,direct:refs/heads/test2,filterText:test2|httpStatuses=[404, 404, 200]|matchedBranchId=|matchedDisplayId=")
                ));
        when(bitbucketClient.branchExists("DCE", "backend", "refs/heads/test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        false,
                        "test2",
                        List.of()
                ));

        BitbucketTest2DemoPrResponse response =
                service.create(caseId, request(true, true));

        assertThat(response.created()).isFalse();
        assertThat(response.blockers())
                .contains("BITBUCKET_TARGET_BRANCH_NOT_FOUND")
                .doesNotContain("BITBUCKET_BRANCH_LOOKUP_FAILED");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
    }

    @Test
    void existingIntegrationBranchRequiresReuseFlag() {
        enableRealActions();
        BitbucketTest2DemoPrRequest noReuse =
                new BitbucketTest2DemoPrRequest(
                        "berkan",
                        "DCE",
                        "backend",
                        "FIZZMS-10228",
                        "test2",
                        "integration/test2/FIZZMS-10228",
                        "[DRAFT] ReplayLab",
                        true,
                        false,
                        true,
                        true
                );
        when(bitbucketClient.branchExists("DCE", "backend", "test2"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "test2",
                        List.of()
                ));
        when(bitbucketClient.branchExists("DCE", "backend",
                "integration/test2/FIZZMS-10228"))
                .thenReturn(new BitbucketBranchCheckResult(
                        true,
                        "integration/test2/FIZZMS-10228",
                        List.of()
                ));

        BitbucketTest2DemoPrResponse response =
                service.create(caseId, noReuse);

        assertThat(response.blockers())
                .contains("BITBUCKET_INTEGRATION_BRANCH_ALREADY_EXISTS");
        verify(gitOperations, never()).commitAndPushIntegrationBranch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private void enableRealActions() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
    }

    private BitbucketTest2DemoPrRequest request(
            boolean confirm,
            boolean guardrails
    ) {
        return new BitbucketTest2DemoPrRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "test2",
                "integration/test2/FIZZMS-10228",
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
