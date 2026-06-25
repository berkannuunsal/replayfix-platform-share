package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.DefectPrTargetedChangeRequest;
import com.etiya.replayfix.api.dto.DefectPrTargetedChangeResponse;
import com.etiya.replayfix.api.dto.PrOutcomeSummaryResponse;
import com.etiya.replayfix.api.dto.PrRuleReviewResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketFileUpdateResult;
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

class DefectPrTargetedChangeServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private BitbucketClient bitbucketClient;
    private ReplayFixProperties properties;
    private PrRuleReviewService prRuleReviewService;
    private BitbucketPrCommentService bitbucketPrCommentService;
    private DefectPrTargetedChangeService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        bitbucketClient = mock(BitbucketClient.class);
        prRuleReviewService = mock(PrRuleReviewService.class);
        bitbucketPrCommentService = mock(BitbucketPrCommentService.class);
        properties = new ReplayFixProperties();
        properties.getIntegrations().getBitbucket().getBranchStrategy()
                .getEnvironmentTargets()
                .put("test2", "Integration/test2/FIZZMS-6686");
        service = new DefectPrTargetedChangeService(
                caseRepository,
                evidenceRepository,
                bitbucketClient,
                properties,
                new ObjectMapper().findAndRegisterModules(),
                prRuleReviewService,
                bitbucketPrCommentService
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(prRuleReviewService.reviewPlannedChange(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(preflightAccept());
        when(bitbucketPrCommentService.comment(any(), any()))
                .thenReturn(summaryComment(true));
    }

    @Test
    void previewDoesNotCreateBranchUpdateFileOrCreatePr() {
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", false);
        mockBranchExists("Integration/test2/CRM-123", false);

        DefectPrTargetedChangeResponse response =
                service.preview(caseId, request("CRM-123", false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.created()).isFalse();
        assertThat(response.filePath())
                .contains("ControllerBackend/src/test/java/")
                .doesNotContain("src/main/java");
        assertThat(response.changeMode()).isEqualTo("APPROVED_REGRESSION_TEST");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
        verify(bitbucketClient, never()).updateFile(any(), any(), any(), any(), any(), any());
        verify(bitbucketClient, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void genericTargetedChangeNamesDoNotUseDemoWording() {
        assertThat(DefectPrTargetedChangeService.class.getSimpleName())
                .doesNotContainIgnoringCase("demo");
        assertThat(DefectPrTargetedChangeRequest.class.getSimpleName())
                .doesNotContainIgnoringCase("demo");
        assertThat(DefectPrTargetedChangeResponse.class.getSimpleName())
                .doesNotContainIgnoringCase("demo");

        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", false);
        mockBranchExists("Integration/test2/CRM-123", false);
        DefectPrTargetedChangeResponse response =
                service.preview(caseId, request("CRM-123", false, false));

        assertThat(response.filePath())
                .endsWith("CRM123RegressionTest.java")
                .doesNotContainIgnoringCase("demo");
        assertThat(response.title())
                .isEqualTo("[DRAFT] ReplayFix CRM-123 fix proposal")
                .doesNotContainIgnoringCase("demo");
    }

    @Test
    void createRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.create(caseId, request("CRM-123", false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_CREATE_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
    }

    @Test
    void createRequiresRealActionConfigEnabled() {
        assertThatThrownBy(() -> service.create(caseId, request("CRM-123", true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void generatedTestOnlyPathMustRemainUnderTestSource() {
        DefectPrTargetedChangeRequest unsafe =
                new DefectPrTargetedChangeRequest(
                        "berkan", "DCE", "backend", "CRM-123", "Safe summary",
                        "test2", "master", "Integration/test2/FIZZMS-6686",
                        "", "",
                        "ControllerBackend/src/main/java/com/company/UserService.java",
                        "APPROVED_REGRESSION_TEST", "[DRAFT] ReplayFix", true,
                        false, false);

        DefectPrTargetedChangeResponse response =
                service.preview(caseId, unsafe);

        assertThat(response.blockers())
                .contains("TEST_FILE_PATH_REQUIRED");
        verify(bitbucketClient, never()).updateFile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvedSourceFixRequiresApprovedEvidence() {
        DefectPrTargetedChangeRequest sourceFix =
                new DefectPrTargetedChangeRequest(
                        "berkan", "DCE", "backend", "CRM-123", "Safe summary",
                        "test2", "master", "Integration/test2/FIZZMS-6686",
                        "", "",
                        "ControllerBackend/src/main/java/com/company/UserService.java",
                        "APPROVED_SOURCE_FIX", "[DRAFT] ReplayFix", true,
                        false, false);

        DefectPrTargetedChangeResponse response =
                service.preview(caseId, sourceFix);

        assertThat(response.blockers())
                .contains("SOURCE_FIX_NOT_APPROVED_FOR_FILE");
    }

    @Test
    void createUsesRestFileUpdateOnBugfixAndIntegrationBranches() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", false);
        mockBranchExists("Integration/test2/CRM-123", false);
        when(bitbucketClient.createBranch(any(), any(), any(), any()))
                .thenReturn(new BitbucketBranchCreateResult(true, false, "branch", List.of()));
        when(bitbucketClient.updateFile(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BitbucketFileUpdateResult(
                        true, "branch", "file", "commit123", List.of()));
        when(bitbucketClient.createPullRequest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PullRequestResult(
                        "77", "https://bitbucket/pr/77",
                        "[DRAFT] ReplayFix CRM-123 fix proposal"));

        DefectPrTargetedChangeResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.pullRequestUrl()).isEqualTo("https://bitbucket/pr/77");
        assertThat(response.prSummaryCommentCreated()).isTrue();
        assertThat(response.prSummaryPreview()).contains("## ReplayLab Summary");
        assertThat(response.commitMessage()).isEqualTo("CRM-123: Safe summary");
        verify(bitbucketClient).createBranch("DCE", "backend", "bugfix/CRM-123", "master");
        verify(bitbucketClient).createBranch(
                "DCE", "backend", "Integration/test2/CRM-123",
                "Integration/test2/FIZZMS-6686");
        verify(bitbucketClient).updateFile(
                eq("DCE"), eq("backend"), eq("bugfix/CRM-123"),
                eq("ControllerBackend/src/test/java/com/etiya/replayfix/generated/CRM123RegressionTest.java"),
                org.mockito.ArgumentMatchers.contains(
                        "ReplayFix generated regression test requires business-specific assertions before enabling"),
                eq("CRM-123: Safe summary"));
        verify(bitbucketClient).updateFile(
                eq("DCE"), eq("backend"), eq("Integration/test2/CRM-123"),
                any(), any(), eq("CRM-123: Safe summary"));
        verify(bitbucketClient, never()).mergeBranches(any(), any(), any(), any());
        verify(bitbucketPrCommentService).comment(eq(caseId), any());
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    @Test
    void preflightBlockerStopsBeforeBranchCreationAndPr() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", false);
        mockBranchExists("Integration/test2/CRM-123", false);
        when(prRuleReviewService.reviewPlannedChange(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(preflightReject());

        DefectPrTargetedChangeResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.created()).isFalse();
        assertThat(response.blockers()).contains("PR_RULE_REVIEW_BLOCKED");
        verify(bitbucketClient, never()).createBranch(any(), any(), any(), any());
        verify(bitbucketClient, never()).updateFile(any(), any(), any(), any(), any(), any());
        verify(bitbucketClient, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
        verify(bitbucketPrCommentService, never()).comment(any(), any());
    }

    @Test
    void mediaTypeFailureReturnsSpecificFileUpdateBlockerAndDoesNotCreatePr() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", true);
        mockBranchExists("Integration/test2/CRM-123", true);
        when(bitbucketClient.updateFile(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BitbucketFileUpdateResult(
                        false,
                        "branch",
                        "file",
                        "",
                        List.of("BITBUCKET_FILE_UPDATE_MEDIA_TYPE_UNSUPPORTED",
                                "Use multipart/form-data or form-url-encoded Bitbucket file update request.")
                ));

        DefectPrTargetedChangeResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.created()).isFalse();
        assertThat(response.blockers())
                .contains("BITBUCKET_BUGFIX_FILE_UPDATE_FAILED",
                        "BITBUCKET_INTEGRATION_FILE_UPDATE_FAILED",
                        "BITBUCKET_FILE_UPDATE_MEDIA_TYPE_UNSUPPORTED");
        assertThat(response.warnings())
                .contains("Use multipart/form-data or form-url-encoded Bitbucket file update request.");
        verify(bitbucketClient, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
        verify(bitbucketPrCommentService, never()).comment(any(), any());
    }

    @Test
    void duplicateDefectKeyIsNormalizedInCommitMessage() {
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/FIZZMS-10228", false);
        mockBranchExists("Integration/test2/FIZZMS-10228", false);
        DefectPrTargetedChangeRequest request =
                new DefectPrTargetedChangeRequest(
                        "berkan", "DCE", "backend", "FIZZMS-10228",
                        "FIZZMS-10228 : FMS-170772//Region mismatch",
                        "test2", "master", "Integration/test2/FIZZMS-6686",
                        "", "", "", "APPROVED_REGRESSION_TEST",
                        "[DRAFT] ReplayFix", true, false, false);

        DefectPrTargetedChangeResponse response =
                service.preview(caseId, request);

        assertThat(response.commitMessage())
                .isEqualTo("FIZZMS-10228: FMS-170772//Region mismatch");
    }

    @Test
    void existingOpenPrIsReused() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", true);
        mockBranchExists("Integration/test2/CRM-123", true);
        when(bitbucketClient.updateFile(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BitbucketFileUpdateResult(
                        true, "branch", "file", "commit123", List.of()));
        when(bitbucketClient.findOpenPullRequest(
                "DCE", "backend", "Integration/test2/CRM-123",
                "Integration/test2/FIZZMS-6686"))
                .thenReturn(new PullRequestResult(
                        "99", "https://bitbucket/pr/99",
                        "[DRAFT] ReplayFix CRM-123 fix proposal"));

        DefectPrTargetedChangeResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.pullRequestUrl()).isEqualTo("https://bitbucket/pr/99");
        assertThat(response.warnings())
                .contains("BITBUCKET_PULL_REQUEST_ALREADY_EXISTS_REUSED");
        assertThat(response.prSummaryCommentCreated()).isTrue();
        verify(bitbucketClient, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
        verify(bitbucketPrCommentService).comment(eq(caseId), any());
    }

    @Test
    void summaryCommentFailureDoesNotFailCreatedPr() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", true);
        mockBranchExists("Integration/test2/CRM-123", true);
        when(bitbucketClient.updateFile(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BitbucketFileUpdateResult(
                        true, "branch", "file", "commit123", List.of()));
        when(bitbucketClient.createPullRequest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PullRequestResult(
                        "77", "https://bitbucket/pr/77",
                        "[DRAFT] ReplayFix CRM-123 fix proposal"));
        when(bitbucketPrCommentService.comment(any(), any()))
                .thenReturn(summaryComment(false));

        DefectPrTargetedChangeResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.prSummaryCommentCreated()).isFalse();
        assertThat(response.warnings()).contains("BITBUCKET_PR_SUMMARY_COMMENT_FAILED");
    }

    @Test
    void responseDoesNotExposeTokenOrSecret() {
        enableRealActions();
        mockBranchExists("master", true);
        mockBranchExists("Integration/test2/FIZZMS-6686", true);
        mockBranchExists("bugfix/CRM-123", true);
        mockBranchExists("Integration/test2/CRM-123", true);
        when(bitbucketClient.updateFile(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BitbucketFileUpdateResult(
                        false, "branch", "file", "",
                        List.of("token=abc123 Authorization bearer secret")));

        DefectPrTargetedChangeResponse response =
                service.create(caseId, request("CRM-123", true, true));

        assertThat(response.toString())
                .doesNotContain("abc123")
                .doesNotContain("bearer")
                .doesNotContain("Authorization bearer secret");
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

    private PrRuleReviewResponse preflightAccept() {
        return new PrRuleReviewResponse(
                caseId,
                "CRM-123",
                "BACKEND",
                "ACCEPT",
                0,
                List.of(),
                "ReviewStatus: ACCEPT\nBlocker violations: 0\n\nViolations of Rules(with rule IDs)\n- None",
                List.of("AGENTS.md", ".agents/AGENTS-Maintainability.md", ".agents/AGENTS-Unit-test.md"),
                List.of(),
                List.of(),
                List.of("Continue guarded draft PR creation.")
        );
    }

    private PrRuleReviewResponse preflightReject() {
        return new PrRuleReviewResponse(
                caseId,
                "CRM-123",
                "BACKEND",
                "REJECT",
                1,
                List.of(new PrRuleReviewResponse.Violation(
                        "R-016",
                        "BLOCKER",
                        "Unit Test",
                        "Generated test contains invalid wording",
                        List.of(new PrRuleReviewResponse.Evidence(
                                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/CRM123RegressionTest.java",
                                "Line 7: class CRM123DemoRegressionTest {"
                        ))
                )),
                "ReviewStatus: REJECT\nBlocker violations: 1\n\nViolations of Rules(with rule IDs)",
                List.of("AGENTS.md"),
                List.of("PR_RULE_REVIEW_BLOCKED"),
                List.of(),
                List.of("Fix rule violation R-016 before creating PR.")
        );
    }

    private PrOutcomeSummaryResponse summaryComment(boolean created) {
        return new PrOutcomeSummaryResponse(
                caseId,
                created ? "77" : "",
                created ? "https://bitbucket/pr/77" : "",
                "## ReplayLab Summary\n\n### Defect\n\n- Defect: CRM-123",
                created,
                created ? "https://bitbucket/pr/77/comments/1" : "",
                false,
                List.of(),
                created ? List.of() : List.of("BITBUCKET_PR_SUMMARY_COMMENT_FAILED"),
                List.of("Review the ReplayLab summary comment on the draft PR."),
                java.time.Instant.now()
        );
    }

    private DefectPrTargetedChangeRequest request(
            String defectKey,
            boolean confirm,
            boolean guardrails
    ) {
        return new DefectPrTargetedChangeRequest(
                "berkan", "DCE", "backend", defectKey, "Safe summary",
                "test2", "master", "", "", "", "",
                "APPROVED_REGRESSION_TEST", "[DRAFT] ReplayFix", true,
                confirm, guardrails);
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
