package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.JenkinsValidationResultRefreshRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationResponse;
import com.etiya.replaylab.api.dto.JenkinsValidationStatusResponse;
import com.etiya.replaylab.api.dto.JenkinsValidationSummaryCommentRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationSummaryRequest;
import com.etiya.replaylab.api.dto.JenkinsValidationSummaryResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.integration.BitbucketClient;
import com.etiya.replaylab.integration.JenkinsClient;
import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.IntegrationModels.JenkinsBuildStatus;
import com.etiya.replaylab.model.IntegrationModels.JenkinsQueueItem;
import com.etiya.replaylab.model.IntegrationModels.JenkinsTriggerResult;
import com.etiya.replaylab.model.IntegrationModels.PullRequestCommentResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JenkinsValidationServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private JenkinsClient jenkinsClient;
    private BitbucketClient bitbucketClient;
    private JiraClient jiraClient;
    private ReplayLabProperties properties;
    private JenkinsValidationService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        jenkinsClient = mock(JenkinsClient.class);
        bitbucketClient = mock(BitbucketClient.class);
        jiraClient = mock(JiraClient.class);
        properties = new ReplayLabProperties();
        service = new JenkinsValidationService(
                caseRepository,
                evidenceRepository,
                jenkinsClient,
                bitbucketClient,
                jiraClient,
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());
    }

    @Test
    void previewDoesNotTriggerJenkinsAndReturnsPlan() {
        JenkinsValidationResponse response = service.preview(caseId, request(false, false, "BACKEND"));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.triggered()).isFalse();
        assertThat(response.jenkinsJobName()).isEqualTo("MODERNIZATION.BACKEND_BUILD_12");
        assertThat(response.plannedParameters())
                .containsEntry("BRANCH", "Integration/test2/FIZZMS-10228")
                .containsEntry("PR_ID", "11397")
                .containsEntry("DEFECT_KEY", "FIZZMS-10228")
                .containsEntry("VALIDATION_MODE", "PR_BUILD")
                .containsEntry("SKIP_DEPLOY", "true");
        assertThat(response.guardrails()).contains("HUMAN_APPROVAL_REQUIRED", "NO_AUTO_DEPLOY");
        assertThat(response.warnings()).contains("PREVIEW_ONLY_NO_JENKINS_TRIGGER");
        verify(jenkinsClient, never()).triggerValidation(any(), any());
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    @Test
    void triggerRequiresConfirmationAndGuardrails() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setJenkinsValidationTriggerEnabled(true);

        assertThatThrownBy(() -> service.trigger(caseId, request(false, false, "BACKEND")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_TRIGGER_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
        verify(jenkinsClient, never()).triggerValidation(any(), any());
    }

    @Test
    void triggerRequiresRealActionsEnabled() {
        properties.getRealActions().setJenkinsValidationTriggerEnabled(true);

        assertThatThrownBy(() -> service.trigger(caseId, request(true, true, "BACKEND")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
        verify(jenkinsClient, never()).triggerValidation(any(), any());
    }

    @Test
    void triggerRequiresJenkinsValidationTriggerEnabled() {
        properties.getRealActions().setEnabled(true);

        assertThatThrownBy(() -> service.trigger(caseId, request(true, true, "BACKEND")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("JENKINS_TRIGGER_DISABLED");
        verify(jenkinsClient, never()).triggerValidation(any(), any());
    }

    @Test
    void triggerCallsJenkinsAndStoresSanitizedEvidenceWhenGuarded() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setJenkinsValidationTriggerEnabled(true);
        when(jenkinsClient.triggerValidation(any(), any()))
                .thenReturn(new JenkinsTriggerResult(
                        true,
                        "https://jenkins/queue/item/1/",
                        "",
                        "QUEUED",
                        List.of()
                ));

        JenkinsValidationResponse response = service.trigger(caseId, request(true, true, "BACKEND"));

        assertThat(response.triggered()).isTrue();
        assertThat(response.previewOnly()).isFalse();
        assertThat(response.jenkinsQueueUrl()).isEqualTo("https://jenkins/queue/item/1/");
        verify(jenkinsClient).triggerValidation(
                eq("MODERNIZATION.BACKEND_BUILD_12"),
                org.mockito.ArgumentMatchers.argThat(parameters ->
                        "Integration/test2/FIZZMS-10228".equals(parameters.get("BRANCH"))
                                && caseId.toString().equals(parameters.get("REPLAYLAB_CASE_ID"))
                                && "true".equals(parameters.get("SKIP_DEPLOY"))
                )
        );
        verify(evidenceRepository).save(any(EvidenceEntity.class));
        assertThat(response.toString().toLowerCase())
                .doesNotContain("authorization")
                .doesNotContain("token=");
    }

    @Test
    void frontendJobNameIsReadFromConfig() {
        properties.getJenkins()
                .getValidation()
                .getFrontend()
                .setDefaultJobName("FRONTEND_VALIDATE");

        JenkinsValidationResponse response = service.preview(caseId, request(false, false, "FRONTEND"));

        assertThat(response.jenkinsJobName()).isEqualTo("FRONTEND_VALIDATE");
    }

    @Test
    void missingFrontendJobConfigReturnsBlocker() {
        JenkinsValidationResponse response = service.preview(caseId, request(false, false, "FRONTEND"));

        assertThat(response.blockers()).contains("JENKINS_VALIDATION_JOB_NOT_CONFIGURED");
    }

    @Test
    void statusReturnsNotTriggeredWhenNoJenkinsEvidenceExists() {
        JenkinsValidationStatusResponse response = service.status(caseId);

        assertThat(response.validationStatus()).isEqualTo("NOT_TRIGGERED");
        assertThat(response.nextActions()).contains("Trigger Jenkins validation preview.");
    }

    @Test
    void refreshResolvesQueuedBuildToBuildUrlAndSuccess() {
        when(jenkinsClient.getQueueItem("https://jenkins/queue/item/1/"))
                .thenReturn(new JenkinsQueueItem(
                        "https://jenkins/queue/item/1/",
                        true,
                        "https://jenkins/job/backend/3056/",
                        "3056",
                        List.of()
                ));
        when(jenkinsClient.getBuildStatusByUrl("https://jenkins/job/backend/3056/"))
                .thenReturn(new JenkinsBuildStatus(
                        "SUCCESS",
                        false,
                        "3056",
                        "https://jenkins/job/backend/3056/",
                        12345,
                        1000,
                        List.of()
                ));

        JenkinsValidationStatusResponse response = service.refresh(caseId, refreshRequest("", "https://jenkins/queue/item/1/"));

        assertThat(response.validationStatus()).isEqualTo("SUCCESS");
        assertThat(response.jenkinsBuildUrl()).isEqualTo("https://jenkins/job/backend/3056/");
        assertThat(response.buildNumber()).isEqualTo("3056");
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    @Test
    void refreshReturnsRunningWhenBuildIsStillBuilding() {
        when(jenkinsClient.getBuildStatusByUrl("https://jenkins/job/backend/3056/"))
                .thenReturn(new JenkinsBuildStatus(
                        "RUNNING",
                        true,
                        "3056",
                        "https://jenkins/job/backend/3056/",
                        0,
                        1000,
                        List.of()
                ));

        JenkinsValidationStatusResponse response = service.refresh(caseId, refreshRequest("https://jenkins/job/backend/3056/", ""));

        assertThat(response.validationStatus()).isEqualTo("RUNNING");
        assertThat(response.nextActions()).contains("Refresh Jenkins validation result.");
    }

    @Test
    void refreshReturnsFailureForFailedBuild() {
        when(jenkinsClient.getBuildStatusByUrl("https://jenkins/job/backend/3056/"))
                .thenReturn(new JenkinsBuildStatus(
                        "FAILURE",
                        false,
                        "3056",
                        "https://jenkins/job/backend/3056/",
                        12345,
                        1000,
                        List.of()
                ));

        JenkinsValidationStatusResponse response = service.refresh(caseId, refreshRequest("https://jenkins/job/backend/3056/", ""));

        assertThat(response.validationStatus()).isEqualTo("FAILURE");
        assertThat(response.nextActions()).contains("Do not merge until Jenkins validation is reviewed.");
    }

    @Test
    void summaryPreviewDoesNotWriteBitbucketOrJiraComments() {
        JenkinsValidationSummaryResponse response = service.summaryPreview(caseId, summaryRequest("SUCCESS"));

        assertThat(response.summaryPreview())
                .contains("## ReplayLab Validation Summary")
                .contains("- Defect: FIZZMS-10228")
                .contains("- PR: https://bitbucket/pr/11397")
                .contains("- Status: ACCEPT")
                .contains("- Job: MODERNIZATION.BACKEND_BUILD_12")
                .contains("- Status: SUCCESS");
        verify(bitbucketClient, never()).addPullRequestComment(any(), any(), any(), any());
        verify(jiraClient, never()).addComment(any(), any());
    }

    @Test
    void failureSummaryWarnsNotToMerge() {
        JenkinsValidationSummaryResponse response = service.summaryPreview(caseId, summaryRequest("FAILURE"));

        assertThat(response.summaryPreview())
                .contains("- Status: FAILURE")
                .contains("PR should not be merged until the failure is reviewed.");
    }

    @Test
    void summaryCommentRequiresConfirmationAndGuardrails() {
        properties.getRealActions().setEnabled(true);

        assertThatThrownBy(() -> service.summaryComment(caseId, commentRequest(false, false, true, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_COMMENT_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
        verify(bitbucketClient, never()).addPullRequestComment(any(), any(), any(), any());
    }

    @Test
    void summaryCommentRequiresRealActionsEnabled() {
        assertThatThrownBy(() -> service.summaryComment(caseId, commentRequest(true, true, true, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void summaryCommentWritesPrOnlyWhenConfirmed() {
        properties.getRealActions().setEnabled(true);
        when(bitbucketClient.addPullRequestComment(any(), any(), any(), any()))
                .thenReturn(new PullRequestCommentResult(
                        true,
                        "1",
                        "https://bitbucket/pr/11397/comments/1",
                        List.of()
                ));

        JenkinsValidationSummaryResponse response =
                service.summaryComment(caseId, commentRequest(true, true, true, false));

        assertThat(response.prCommentCreated()).isTrue();
        assertThat(response.jiraCommentCreated()).isFalse();
        verify(bitbucketClient).addPullRequestComment(eq("DCE"), eq("backend"), eq("11397"), any());
        verify(jiraClient, never()).addComment(any(), any());
    }

    @Test
    void summaryCommentWritesJiraOnlyWhenRequested() {
        properties.getRealActions().setEnabled(true);

        JenkinsValidationSummaryResponse response =
                service.summaryComment(caseId, commentRequest(true, true, false, true));

        assertThat(response.prCommentCreated()).isFalse();
        assertThat(response.jiraCommentCreated()).isTrue();
        verify(bitbucketClient, never()).addPullRequestComment(any(), any(), any(), any());
        verify(jiraClient).addComment(eq("FIZZMS-10228"), any());
    }

    @Test
    void triggerSanitizesJenkinsWarnings() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setJenkinsValidationTriggerEnabled(true);
        when(jenkinsClient.triggerValidation(any(), any()))
                .thenReturn(new JenkinsTriggerResult(
                        false,
                        "",
                        "",
                        "FAILED",
                        List.of("Authorization bearer token secret")
                ));

        JenkinsValidationResponse response = service.trigger(caseId, request(true, true, "BACKEND"));

        assertThat(response.triggered()).isFalse();
        assertThat(response.warnings()).contains("JENKINS_VALIDATION_TRIGGER_FAILED");
        assertThat(response.toString().toLowerCase())
                .doesNotContain("authorization")
                .doesNotContain("bearer")
                .doesNotContain("token=");
    }

    private JenkinsValidationRequest request(boolean confirm, boolean guardrails, String repositoryType) {
        return new JenkinsValidationRequest(
                "berkan",
                "DCE",
                "backend",
                repositoryType,
                "FIZZMS-10228",
                "FMS-170772//Region mismatch",
                "11397",
                "https://bitbucket/projects/DCE/repos/backend/pull-requests/11397",
                "Integration/test2/FIZZMS-10228",
                "Integration/test2/FIZZMS-6686",
                List.of("ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228RegressionTest.java"),
                "PR_BUILD",
                confirm,
                guardrails
        );
    }

    private JenkinsValidationResultRefreshRequest refreshRequest(String buildUrl, String queueUrl) {
        return new JenkinsValidationResultRefreshRequest(
                "berkan",
                "FIZZMS-10228",
                "MODERNIZATION.BACKEND_BUILD_12",
                queueUrl,
                buildUrl,
                "",
                "11397",
                "https://bitbucket/pr/11397",
                "Integration/test2/FIZZMS-10228",
                "Integration/test2/FIZZMS-6686"
        );
    }

    private JenkinsValidationSummaryRequest summaryRequest(String status) {
        return new JenkinsValidationSummaryRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "FMS-170772//Region mismatch",
                "11397",
                "https://bitbucket/pr/11397",
                "Integration/test2/FIZZMS-10228",
                "Integration/test2/FIZZMS-6686",
                "MODERNIZATION.BACKEND_BUILD_12",
                "https://jenkins/job/backend/3056/",
                status,
                List.of("ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228RegressionTest.java"),
                "ACCEPT",
                0
        );
    }

    private JenkinsValidationSummaryCommentRequest commentRequest(
            boolean confirm,
            boolean guardrails,
            boolean commentToPr,
            boolean commentToJira
    ) {
        return new JenkinsValidationSummaryCommentRequest(
                "berkan",
                "DCE",
                "backend",
                "11397",
                "https://bitbucket/pr/11397",
                "FIZZMS-10228",
                commentToPr,
                commentToJira,
                confirm,
                guardrails
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setStatus(ReplayCaseStatus.AWAITING_HUMAN_REVIEW);
        return entity;
    }
}
