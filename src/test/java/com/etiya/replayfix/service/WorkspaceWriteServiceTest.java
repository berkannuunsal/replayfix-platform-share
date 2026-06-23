package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ApprovedWritePlanFile;
import com.etiya.replayfix.api.dto.ApprovedWritePlanResponse;
import com.etiya.replayfix.api.dto.WorkspaceWriteApprovalRequest;
import com.etiya.replayfix.api.dto.WorkspaceWriteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceWriteServiceTest {

    @TempDir
    Path tempDir;

    private UUID caseId;
    private ApprovedWritePlanService approvedWritePlanService;
    private WorkspaceWriteService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        approvedWritePlanService = mock(ApprovedWritePlanService.class);
        service = new WorkspaceWriteService(approvedWritePlanService, tempDir);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(approvedWritePlanService.plan(
                eq(caseId),
                eq(null),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(approvedPlan(Map.of(), Map.of()));
    }

    @Test
    void previewDoesNotWriteFilesAndIncludesPlannedDrafts() {
        WorkspaceWriteResponse response =
                service.preview(caseId, true, true, true);

        assertThat(response.writeStatus()).isEqualTo("PREVIEW_READY");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.filesWritten()).isEmpty();
        assertThat(response.filesPlanned())
                .extracting("fileType")
                .contains("REGRESSION_TEST", "SOURCE_FIX");
        assertThat(asJson(response))
                .contains("FIZZMS-10228")
                .contains("FIZZMS10228UpdateUserRegressionTest");
        assertThat(Files.exists(tempDir.resolve(response.workspacePath())))
                .isFalse();
    }

    @Test
    void applyWithDryRunTrueDoesNotWriteFiles() {
        WorkspaceWriteResponse response = service.apply(
                caseId,
                approval(),
                true,
                true,
                true
        );

        assertThat(response.writeStatus()).isEqualTo("BLOCKED_BY_DRY_RUN");
        assertThat(response.filesWritten()).isEmpty();
        assertThat(Files.exists(tempDir.resolve(response.workspacePath())))
                .isFalse();
    }

    @Test
    void applyWithoutApprovalIsBlocked() {
        WorkspaceWriteResponse response = service.apply(
                caseId,
                null,
                false,
                true,
                true
        );

        assertThat(response.writeStatus())
                .isEqualTo("BLOCKED_BY_MISSING_APPROVAL");
        assertThat(response.filesWritten()).isEmpty();
        assertThat(response.approvalPresent()).isFalse();
    }

    @Test
    void applyWithApprovalWritesOnlyUnderWorkspace() {
        WorkspaceWriteResponse response = service.apply(
                caseId,
                approval(),
                false,
                true,
                true
        );

        assertThat(response.writeStatus()).isEqualTo("WRITTEN_TO_WORKSPACE");
        assertThat(response.filesWritten())
                .extracting("fileType")
                .contains("REGRESSION_TEST", "SOURCE_FIX");

        Path workspaceRoot = tempDir.resolve(response.workspacePath())
                .normalize();
        for (var file : response.filesWritten()) {
            Path written = workspaceRoot.resolve(file.relativePath())
                    .normalize();
            assertThat(written).startsWith(workspaceRoot);
            assertThat(Files.exists(written)).isTrue();
        }
    }

    @Test
    void pathTraversalIsRejected() {
        when(approvedWritePlanService.plan(
                eq(caseId),
                eq(null),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(approvedPlan(
                Map.of("workspaceWriteRelativePath", "../escape.java"),
                Map.of()
        ));

        WorkspaceWriteResponse response = service.apply(
                caseId,
                approval(),
                false,
                true,
                true
        );

        assertThat(response.writeStatus()).isEqualTo("BLOCKED_BY_PATH_GUARD");
        assertThat(response.filesWritten()).isEmpty();
        assertThat(response.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("PATH_TRAVERSAL_REJECTED"));
    }

    @Test
    void absolutePathIsRejected() {
        when(approvedWritePlanService.plan(
                eq(caseId),
                eq(null),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(approvedPlan(
                Map.of("workspaceWriteRelativePath", "C:/tmp/evil.java"),
                Map.of()
        ));

        WorkspaceWriteResponse response = service.apply(
                caseId,
                approval(),
                false,
                true,
                true
        );

        assertThat(response.writeStatus()).isEqualTo("BLOCKED_BY_PATH_GUARD");
        assertThat(response.filesWritten()).isEmpty();
        assertThat(response.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("ABSOLUTE_PATH_REJECTED"));
    }

    @Test
    void generatedContentDoesNotExposeSensitiveValues() {
        WorkspaceWriteResponse response =
                service.preview(caseId, true, true, true);

        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("rawProductionPayload")
                .doesNotContain("raw prompt")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("secret");
    }

    @Test
    void responseIncludesRequiredGuardrails() {
        WorkspaceWriteResponse response =
                service.preview(caseId, true, true, true);

        assertThat(response.guardrails()).contains(
                "WORKSPACE_ONLY_WRITE",
                "HUMAN_APPROVAL_REQUIRED",
                "NO_DIRECT_TEST2_COMMIT",
                "NO_AUTO_PR",
                "NO_AUTO_JENKINS",
                "PATH_TRAVERSAL_PROTECTION"
        );
    }

    @Test
    void noBranchCommitOrJenkinsActionIsTriggered() {
        service.apply(caseId, approval(), false, true, true);

        verify(approvedWritePlanService)
                .plan(eq(caseId), eq(null), eq(true), eq(true), eq(true));
    }

    private WorkspaceWriteApprovalRequest approval() {
        return new WorkspaceWriteApprovalRequest(
                "unit-test",
                "approval-1",
                "Workspace-only validation. No commit, no PR, no Jenkins.",
                true
        );
    }

    private ApprovedWritePlanResponse approvedPlan(
            Map<String, Object> testMetadata,
            Map<String, Object> fixMetadata
    ) {
        String workspace = "work/" + caseId + "/repositories/backend";
        return new ApprovedWritePlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "BLOCKED_BY_MISSING_APPROVAL",
                true,
                true,
                true,
                "DCE/backend",
                "test2",
                "bugfix/FIZZMS-10228-replayfix",
                workspace,
                List.of(
                        new ApprovedWritePlanFile(
                                "REGRESSION_TEST",
                                workspace + "/src/test/java/Test.java",
                                "UserServiceImpl",
                                "updateUser",
                                "/user/region/update",
                                "",
                                "DRAFT",
                                false,
                                testMetadata
                        ),
                        new ApprovedWritePlanFile(
                                "SOURCE_FIX",
                                workspace + "/.replayfix/drafts/FIX_PLAN.md",
                                "UserServiceImpl",
                                "updateUser",
                                "/user/region/update",
                                "VALIDATION_GUARD",
                                "DRAFT",
                                false,
                                fixMetadata
                        )
                ),
                List.of(),
                List.of("mvn clean compile -DskipTests"),
                List.of(
                        PatchPlanCandidateService.REPLAY_REPRODUCTION,
                        PatchPlanCandidateService.FAILING_REGRESSION_TEST,
                        PatchPlanCandidateService.JENKINS_VALIDATION
                ),
                List.of("WORKSPACE_ONLY_WRITE"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private String asJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
