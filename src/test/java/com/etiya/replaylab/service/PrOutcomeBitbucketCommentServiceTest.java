package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.PrOutcomeSummaryRequest;
import com.etiya.replaylab.api.dto.PrOutcomeSummaryResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.model.IntegrationModels.PullRequestCommentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrOutcomeBitbucketCommentServiceTest {

    private UUID caseId;
    private BitbucketClient bitbucketClient;
    private ReplayLabProperties properties;
    private BitbucketPrCommentService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        bitbucketClient = mock(BitbucketClient.class);
        properties = new ReplayLabProperties();
        service = new BitbucketPrCommentService(
                bitbucketClient,
                properties,
                new ReplayLabPrOutcomeSummaryService()
        );
    }

    @Test
    void previewDoesNotCallBitbucketWriteApi() {
        PrOutcomeSummaryResponse response = service.preview(caseId, request(false, false));

        assertThat(response.summaryPreview()).contains("## ReplayLab Summary");
        assertThat(response.prCommentCreated()).isFalse();
        assertThat(response.warnings()).contains("PREVIEW_ONLY_NO_PR_COMMENT_CREATED");
        verify(bitbucketClient, never()).addPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void commentRequiresConfirmCreateAndGuardrails() {
        enableRealActions();

        PrOutcomeSummaryResponse response = service.comment(caseId, request(false, false));

        assertThat(response.blockers())
                .contains("CONFIRM_CREATE_REQUIRED", "GUARDRAILS_ACCEPTED_REQUIRED");
        verify(bitbucketClient, never()).addPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void disabledRealActionsBlocksComment() {
        PrOutcomeSummaryResponse response = service.comment(caseId, request(true, true));

        assertThat(response.blockers()).contains("REAL_ACTIONS_DISABLED");
        verify(bitbucketClient, never()).addPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void commentCreatesBitbucketPrCommentWhenGuarded() {
        enableRealActions();
        when(bitbucketClient.addPullRequestComment(any(), any(), any(), any()))
                .thenReturn(new PullRequestCommentResult(
                        true,
                        "12",
                        "https://bitbucket/pr/11397/comments/12",
                        List.of()
                ));

        PrOutcomeSummaryResponse response = service.comment(caseId, request(true, true));

        assertThat(response.prCommentCreated()).isTrue();
        assertThat(response.prSummaryCommentUrl())
                .isEqualTo("https://bitbucket/pr/11397/comments/12");
        verify(bitbucketClient).addPullRequestComment(
                eq("DCE"),
                eq("backend"),
                eq("11397"),
                org.mockito.ArgumentMatchers.contains("## ReplayLab Summary")
        );
    }

    @Test
    void commentFailureReturnsWarningWithoutToken() {
        enableRealActions();
        when(bitbucketClient.addPullRequestComment(any(), any(), any(), any()))
                .thenReturn(new PullRequestCommentResult(
                        false,
                        "",
                        "",
                        List.of("token=abc Authorization bearer secret")
                ));

        PrOutcomeSummaryResponse response = service.comment(caseId, request(true, true));

        assertThat(response.prCommentCreated()).isFalse();
        assertThat(response.warnings()).contains("BITBUCKET_PR_SUMMARY_COMMENT_FAILED");
        assertThat(response.toString().toLowerCase())
                .doesNotContain("abc")
                .doesNotContain("authorization")
                .doesNotContain("bearer")
                .doesNotContain("secret");
    }

    private void enableRealActions() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketPrCreateEnabled(true);
    }

    private PrOutcomeSummaryRequest request(boolean confirm, boolean guardrails) {
        return new PrOutcomeSummaryRequest(
                "berkan",
                "DCE",
                "backend",
                "11397",
                "https://bitbucket/pr/11397",
                "FIZZMS-10228",
                "FMS-170772//Region mismatch",
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "TARGETED_TEST_CHANGE",
                "ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228RegressionTest.java",
                "49cf7a",
                "937df3",
                "ACCEPT",
                0,
                List.of("AGENTS.md", ".agents/AGENTS-Maintainability.md"),
                confirm,
                guardrails
        );
    }
}
