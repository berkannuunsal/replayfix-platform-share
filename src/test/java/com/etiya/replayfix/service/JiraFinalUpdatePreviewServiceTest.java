package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replayfix.api.dto.GeneratedDraftReviewedFile;
import com.etiya.replayfix.api.dto.JiraFinalUpdatePreviewResponse;
import com.etiya.replayfix.api.dto.PatchPlanCandidateResponse;
import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JiraFinalUpdatePreviewServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private PatchPlanCandidateService patchPlanCandidateService;
    private GeneratedDraftReviewService generatedDraftReviewService;
    private TestExecutionPlanService testExecutionPlanService;
    private JiraFinalUpdatePreviewService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        patchPlanCandidateService = mock(PatchPlanCandidateService.class);
        generatedDraftReviewService = mock(GeneratedDraftReviewService.class);
        testExecutionPlanService = mock(TestExecutionPlanService.class);
        service = new JiraFinalUpdatePreviewService(
                caseRepository,
                patchPlanCandidateService,
                generatedDraftReviewService,
                testExecutionPlanService
        );
        objectMapper = new ObjectMapper().findAndRegisterModules();

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase()));
        when(patchPlanCandidateService.candidate(
                eq(caseId),
                eq(false),
                anyBoolean(),
                eq(true)
        )).thenReturn(patchPlan());
        when(generatedDraftReviewService.review(eq(caseId), eq(false), eq(0)))
                .thenReturn(draftReview());
        when(testExecutionPlanService.plan(eq(caseId), eq(true), eq(true), eq(true)))
                .thenReturn(testPlan());
    }

    @Test
    void returnsPreviewOnlyHypothesisFinalUpdate() {
        JiraFinalUpdatePreviewResponse response =
                service.preview(caseId, true, true, true, true);

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.jiraKey()).isEqualTo("FIZZMS-10228");
        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.previewOnly()).isTrue();
        assertThat(response.shouldPublish()).isFalse();
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void includesRequiredCommentSectionsAndMissingEvidence() {
        JiraFinalUpdatePreviewResponse response =
                service.preview(caseId, true, true, true, true);

        assertThat(response.commentSections())
                .extracting("title")
                .contains(
                        "Summary",
                        "Evidence",
                        "Source reasoning",
                        "Regression test draft",
                        "Replay environment readiness",
                        "Patch plan candidate",
                        "Approved write plan",
                        "Workspace write status",
                        "Generated draft review",
                        "Test execution plan",
                        "Missing evidence",
                        "Next action",
                        "Human approval required"
                );
        assertThat(response.missingEvidence()).contains(
                "REPLAY_REPRODUCTION",
                "FAILING_REGRESSION_TEST"
        );
    }

    @Test
    void passesIncludeFlagsToPlanningServices() {
        service.preview(caseId, false, false, false, false);

        verify(patchPlanCandidateService)
                .candidate(eq(caseId), eq(false), eq(false), eq(true));
        verifyNoInteractions(generatedDraftReviewService);
    }

    @Test
    void omitsOptionalSectionsWhenDisabled() {
        JiraFinalUpdatePreviewResponse response =
                service.preview(caseId, false, false, false, false);

        assertThat(response.commentSections())
                .extracting("title")
                .doesNotContain(
                        "Source reasoning",
                        "Replay environment readiness",
                        "Patch plan candidate",
                        "Approved write plan",
                        "Test execution plan"
                )
                .contains("Summary", "Evidence", "Missing evidence", "Next action");
    }

    @Test
    void doesNotExposeRawReasoningPromptPayloadOrSecrets() {
        JiraFinalUpdatePreviewResponse response =
                service.preview(caseId, true, true, true, true);

        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("raw prompt")
                .doesNotContain("rawProductionPayload")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token");
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.CONTEXT_READY);
        return entity;
    }

    private PatchPlanCandidateResponse patchPlan() {
        return new PatchPlanCandidateResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "DRAFT",
                false,
                true,
                "DCE/backend",
                "test2",
                "bugfix/FIZZMS-10228-replayfix",
                List.of("ControllerBackend/src/main/java/company/UserServiceImpl.java"),
                List.of("UserServiceImpl#updateUser"),
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                "VALIDATION_GUARD",
                Map.of("writesCode", false),
                List.of(),
                List.of(Map.of("testType", "API_INTEGRATION")),
                List.of("USER_REGION_STATE"),
                Map.of(
                        "readinessStatus",
                        "BLOCKED",
                        "requestApproved",
                        false,
                        "realProvisioningEnabled",
                        false
                ),
                List.of("REPLAY_REPRODUCTION", "FAILING_REGRESSION_TEST"),
                List.of(
                        "SAFE_WARNING",
                        "reasoning_content must stay filtered",
                        "password marker must stay filtered"
                ),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private GeneratedDraftReviewResponse draftReview() {
        return new GeneratedDraftReviewResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "READY_FOR_HUMAN_REVIEW",
                "work/" + caseId + "/repositories/backend",
                List.of(new GeneratedDraftReviewedFile(
                        "REGRESSION_TEST",
                        "src/test/java/com/company/commerce/backend/crm/user/FIZZMS10228UpdateUserRegressionTest.java",
                        true,
                        true,
                        512,
                        "",
                        List.of(),
                        List.of(),
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of("Review draft content manually."),
                true,
                false,
                false,
                false,
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }

    private TestExecutionPlanResponse testPlan() {
        return new TestExecutionPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "BLOCKED_BY_MISSING_APPROVAL",
                true,
                true,
                false,
                "work/" + caseId + "/repositories/backend",
                List.of(
                        "mvn clean compile -DskipTests",
                        "mvn test -Dtest=FIZZMS10228UpdateUserRegressionTest"
                ),
                List.of("FIZZMS10228UpdateUserRegressionTest"),
                List.of("local", "test2-replay"),
                List.of("WireMock replay mocks"),
                List.of("USER_REGION_STATE"),
                List.of("HUMAN_APPROVAL_REQUIRED"),
                List.of("NO_AUTO_TEST_EXECUTION"),
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
