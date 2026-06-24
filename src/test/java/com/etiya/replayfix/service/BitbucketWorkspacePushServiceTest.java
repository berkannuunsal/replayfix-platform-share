package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketWorkspacePushRequest;
import com.etiya.replayfix.api.dto.BitbucketWorkspacePushResponse;
import com.etiya.replayfix.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replayfix.api.dto.GeneratedDraftReviewedFile;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

class BitbucketWorkspacePushServiceTest {

    @TempDir
    Path tempDir;

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private GeneratedDraftReviewService draftReviewService;
    private WorkspaceGitOperations gitOperations;
    private ReplayFixProperties properties;
    private BitbucketWorkspacePushService service;

    @BeforeEach
    void setUp() throws Exception {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        draftReviewService = mock(GeneratedDraftReviewService.class);
        gitOperations = mock(WorkspaceGitOperations.class);
        properties = new ReplayFixProperties();
        service = new BitbucketWorkspacePushService(
                caseRepository,
                evidenceRepository,
                draftReviewService,
                gitOperations,
                properties,
                new ObjectMapper().findAndRegisterModules(),
                tempDir
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        createWorkspace("safe draft content");
        when(draftReviewService.review(caseId, false, 0))
                .thenReturn(readyReview());
        when(gitOperations.status(workspaceRoot()))
                .thenReturn(new WorkspaceGitOperations.WorkspaceGitStatus(
                        true,
                        true,
                        "base",
                        " M file",
                        ""
                ));
    }

    @Test
    void previewDoesNotPush() {
        BitbucketWorkspacePushResponse response =
                service.preview(caseId, request(false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.executed()).isFalse();
        assertThat(response.bugfixBranch()).isEqualTo("bugfix/FIZZMS-10228");
        verify(gitOperations, never()).pushApprovedChanges(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void executeRequiresConfirmPushAndGuardrails() {
        assertThatThrownBy(() -> service.execute(caseId, request(false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_PUSH_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
    }

    @Test
    void executeDisabledReturnsRealActionsDisabled() {
        assertThatThrownBy(() -> service.execute(caseId, request(true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
    }

    @Test
    void protectedBranchPushIsBlocked() {
        BitbucketWorkspacePushRequest unsafe = new BitbucketWorkspacePushRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "",
                "",
                "master",
                "test2",
                "master",
                "integration/test2/FIZZMS-10228",
                "ReplayFix: FIZZMS-10228 guarded draft fix",
                false,
                false
        );

        BitbucketWorkspacePushResponse response = service.preview(caseId, unsafe);

        assertThat(response.blockers())
                .contains("PROTECTED_BRANCH_PUSH_BLOCKED");
    }

    @Test
    void branchPrefixesAreEnforced() {
        BitbucketWorkspacePushRequest unsafe = new BitbucketWorkspacePushRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "",
                "",
                "master",
                "test2",
                "feature/FIZZMS-10228",
                "feature/test2/FIZZMS-10228",
                "ReplayFix: FIZZMS-10228 guarded draft fix",
                false,
                false
        );

        BitbucketWorkspacePushResponse response = service.preview(caseId, unsafe);

        assertThat(response.blockers()).contains(
                "BITBUCKET_BUGFIX_BRANCH_NAME_INVALID",
                "BITBUCKET_INTEGRATION_BRANCH_NAME_INVALID"
        );
    }

    @Test
    void missingApprovedWorkspaceWriteBlocks() {
        when(draftReviewService.review(caseId, false, 0))
                .thenReturn(new GeneratedDraftReviewResponse(
                        caseId,
                        "FIZZMS-10228",
                        "HYPOTHESIS",
                        "NO_GENERATED_FILES",
                        "work/" + caseId + "/repositories/backend",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        false,
                        false,
                        false,
                        List.of(),
                        Instant.now()
                ));

        BitbucketWorkspacePushResponse response =
                service.preview(caseId, request(false, false));

        assertThat(response.blockers())
                .contains("APPROVED_WORKSPACE_WRITE_MISSING");
    }

    @Test
    void sensitiveMarkerInWorkspaceBlocks() throws Exception {
        createWorkspace("password = bad");

        BitbucketWorkspacePushResponse response =
                service.preview(caseId, request(false, false));

        assertThat(response.blockers())
                .contains("WORKSPACE_DIFF_CONTAINS_FORBIDDEN_SENSITIVE_MARKER");
        assertThat(String.join(",", response.warnings()))
                .doesNotContain("bad");
    }

    @Test
    void successfulPushStoresCommitShaEvidence() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        when(gitOperations.pushApprovedChanges(
                workspaceRoot(),
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                "ReplayFix: FIZZMS-10228 guarded draft fix"
        )).thenReturn(new WorkspaceGitOperations.WorkspaceGitPushResult(
                true,
                true,
                true,
                true,
                false,
                "abc123",
                "",
                ""
        ));

        BitbucketWorkspacePushResponse response =
                service.execute(caseId, request(true, true));

        assertThat(response.executed()).isTrue();
        assertThat(response.commitSha()).isEqualTo("abc123");
        verify(evidenceRepository).save(any(EvidenceEntity.class));
        verify(caseRepository).save(any(ReplayCaseEntity.class));
    }

    @Test
    void mergeConflictReturnsHumanBlocker() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setBitbucketBranchCreateEnabled(true);
        properties.getRealActions().setBitbucketPushEnabled(true);
        when(gitOperations.pushApprovedChanges(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new WorkspaceGitOperations.WorkspaceGitPushResult(
                true,
                false,
                true,
                false,
                true,
                "abc123",
                "",
                "BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN"
        ));

        BitbucketWorkspacePushResponse response =
                service.execute(caseId, request(true, true));

        assertThat(response.mergeConflict()).isTrue();
        assertThat(response.blockers())
                .contains("BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN");
    }

    private void createWorkspace(String content) throws Exception {
        Path root = workspaceRoot();
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve(".replayfix/drafts/FIZZMS-10228"));
        Files.writeString(
                root.resolve(".replayfix/drafts/FIZZMS-10228/fix.md"),
                content
        );
    }

    private Path workspaceRoot() {
        return tempDir.resolve("work")
                .resolve(caseId.toString())
                .resolve("repositories")
                .resolve("backend")
                .normalize();
    }

    private GeneratedDraftReviewResponse readyReview() {
        return new GeneratedDraftReviewResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "READY_FOR_HUMAN_REVIEW",
                "work/" + caseId + "/repositories/backend",
                List.of(new GeneratedDraftReviewedFile(
                        "SOURCE_FIX",
                        ".replayfix/drafts/FIZZMS-10228/fix.md",
                        true,
                        true,
                        10,
                        "safe",
                        List.of(),
                        List.of(),
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                false,
                false,
                false,
                List.of(),
                Instant.now()
        );
    }

    private BitbucketWorkspacePushRequest request(
            boolean confirm,
            boolean guardrails
    ) {
        return new BitbucketWorkspacePushRequest(
                "berkan",
                "DCE",
                "backend",
                "FIZZMS-10228",
                "",
                "",
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                "ReplayFix: FIZZMS-10228 guarded draft fix",
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
