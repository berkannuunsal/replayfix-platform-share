package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ApprovedWritePlanFile;
import com.etiya.replaylab.api.dto.ApprovedWritePlanResponse;
import com.etiya.replaylab.api.dto.GeneratedDraftReviewResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedDraftReviewServiceTest {

    @TempDir
    Path tempDir;

    private UUID caseId;
    private ApprovedWritePlanService approvedWritePlanService;
    private GeneratedDraftReviewService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        approvedWritePlanService = mock(ApprovedWritePlanService.class);
        service = new GeneratedDraftReviewService(
                approvedWritePlanService,
                tempDir
        );
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
    void returnsNoGeneratedFilesIfWorkspaceFilesDoNotExist() {
        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus()).isEqualTo("NO_GENERATED_FILES");
        assertThat(response.reviewedFiles())
                .extracting("fileType")
                .contains("REGRESSION_TEST", "SOURCE_FIX");
        assertThat(response.missingItems()).contains(
                "MISSING_REGRESSION_TEST",
                "MISSING_SOURCE_FIX"
        );
    }

    @Test
    void returnsReadyForHumanReviewWhenExpectedFilesExistAndAreSafe()
            throws Exception {
        writeSafeDrafts();

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus()).isEqualTo("READY_FOR_HUMAN_REVIEW");
        assertThat(response.securityFindings()).isEmpty();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.shouldRunTests()).isFalse();
        assertThat(response.shouldCreateBranch()).isFalse();
        assertThat(response.shouldOpenPr()).isFalse();
    }

    @Test
    void blocksWhenGeneratedFileContainsSensitiveMarkers() throws Exception {
        writeSafeDrafts();
        Files.writeString(
                workspaceRoot().resolve(testPath()),
                "Authorization: fake-token",
                StandardCharsets.UTF_8
        );

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus())
                .isEqualTo("BLOCKED_BY_SECURITY_FINDINGS");
        assertThat(response.securityFindings())
                .contains("SECURITY_MARKER_FOUND");
        assertThat(asJson(response))
                .doesNotContain("fake-token")
                .doesNotContain("Authorization");
    }

    @Test
    void blocksWhenFilePathEscapesWorkspace() {
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

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus()).isEqualTo("BLOCKED_BY_PATH_GUARD");
        assertThat(response.securityFindings())
                .contains("PATH_TRAVERSAL_REJECTED");
    }

    @Test
    void detectsMissingRegressionTestDraft() throws Exception {
        writeFixDraft(safeFixContent());

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus())
                .isEqualTo("BLOCKED_BY_MISSING_TEST_DRAFT");
        assertThat(response.missingItems()).contains("MISSING_REGRESSION_TEST");
    }

    @Test
    void detectsMissingFixDraft() throws Exception {
        writeTestDraft(safeTestContent());

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus())
                .isEqualTo("BLOCKED_BY_MISSING_FIX_DRAFT");
        assertThat(response.missingItems()).contains("MISSING_SOURCE_FIX");
    }

    @Test
    void detectsMissingEndpointReference() throws Exception {
        writeSafeDrafts();
        writeTestDraft(safeTestContent().replace("/user/region/update", "/x"));

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.qualityFindings())
                .contains("QUALITY_ITEM_MISSING:TARGET_ENDPOINT");
        assertThat(response.missingItems()).contains("TARGET_ENDPOINT");
    }

    @Test
    void detectsMissingUserServiceImplUpdateUserReference() throws Exception {
        writeSafeDrafts();
        writeFixDraft(safeFixContent()
                .replace("UserServiceImpl", "OtherService")
                .replace("updateUser", "otherMethod"));

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.qualityFindings()).contains(
                "QUALITY_ITEM_MISSING:TARGET_CLASS",
                "QUALITY_ITEM_MISSING:TARGET_METHOD"
        );
        assertThat(response.missingItems()).contains(
                "TARGET_CLASS",
                "TARGET_METHOD"
        );
    }

    @Test
    void limitsContentPreviewToMaxPreviewChars() throws Exception {
        writeSafeDrafts();

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 40);

        assertThat(response.reviewedFiles())
                .allSatisfy(file -> assertThat(file.contentPreview().length())
                        .isLessThanOrEqualTo(40));
    }

    @Test
    void doesNotExposeRawReasoningContent() throws Exception {
        writeSafeDrafts();
        writeFixDraft(safeFixContent() + "\nreasoning_content: hidden");

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.reviewStatus())
                .isEqualTo("BLOCKED_BY_SECURITY_FINDINGS");
        assertThat(asJson(response)).doesNotContain("reasoning_content");
    }

    @Test
    void alwaysRequiresHumanApprovalAndDoesNotAuthorizeActions()
            throws Exception {
        writeSafeDrafts();

        GeneratedDraftReviewResponse response =
                service.review(caseId, true, 1200);

        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.shouldRunTests()).isFalse();
        assertThat(response.shouldCreateBranch()).isFalse();
        assertThat(response.shouldOpenPr()).isFalse();
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
                "bugfix/FIZZMS-10228-replaylab",
                workspace,
                List.of(
                        new ApprovedWritePlanFile(
                                "REGRESSION_TEST",
                                workspace + "/" + testPath(),
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
                                workspace + "/" + fixPath(),
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
                        PatchPlanCandidateService.FAILING_REGRESSION_TEST
                ),
                List.of("WORKSPACE_ONLY_WRITE"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private void writeSafeDrafts() throws Exception {
        writeTestDraft(safeTestContent());
        writeFixDraft(safeFixContent());
    }

    private void writeTestDraft(String content) throws Exception {
        Path path = workspaceRoot().resolve(testPath());
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private void writeFixDraft(String content) throws Exception {
        Path path = workspaceRoot().resolve(fixPath());
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String safeTestContent() {
        return """
                // FIZZMS-10228
                // Draft regression test
                // Endpoint: /user/region/update
                // Scenario: preferredProvince / region mismatch
                // TODO: use sanitized test data only.
                // Assertion section: assert validation or mapping result.
                class FIZZMS10228UpdateUserRegressionTest {
                }
                """;
    }

    private String safeFixContent() {
        return """
                # Fix plan
                Target class: UserServiceImpl
                Target method: updateUser
                Recommended change type: VALIDATION_GUARD
                Missing evidence:
                - REPLAY_REPRODUCTION
                Human approval required before any write.
                Guardrail: NO_DIRECT_TEST2_COMMIT
                """;
    }

    private Path workspaceRoot() {
        return tempDir.resolve("work")
                .resolve(caseId.toString())
                .resolve("repositories")
                .resolve("backend");
    }

    private String testPath() {
        return "src/test/java/com/company/commerce/backend/crm/user/"
                + "FIZZMS10228UpdateUserRegressionTest.java";
    }

    private String fixPath() {
        return ".replaylab/drafts/FIZZMS-10228/"
                + "UserServiceImpl_updateUser_FIX_PLAN.md";
    }

    private String asJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
