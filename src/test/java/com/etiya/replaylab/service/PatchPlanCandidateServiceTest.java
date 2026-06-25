package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replaylab.api.dto.PatchPlanCandidateResponse;
import com.etiya.replaylab.api.dto.RecommendedCodeChange;
import com.etiya.replaylab.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.domain.ReplayEnvironmentRequestEntity;
import com.etiya.replaylab.model.FixPlanCandidate;
import com.etiya.replaylab.model.FixPlanResponse;
import com.etiya.replaylab.model.RegressionTestDbValidationRequirement;
import com.etiya.replaylab.model.RegressionTestDraftResponse;
import com.etiya.replaylab.model.RegressionTestScenario;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayEnvironmentRequestRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatchPlanCandidateServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private RegressionTestDraftService regressionTestDraftService;
    private FixPlanService fixPlanService;
    private CodeChangeAdvisoryService advisoryService;
    private ReplayEnvironmentRequestRepository replayRequestRepository;
    private ReplayEnvironmentRequestService replayRequestService;
    private PatchPlanCandidateService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        regressionTestDraftService = mock(RegressionTestDraftService.class);
        fixPlanService = mock(FixPlanService.class);
        advisoryService = mock(CodeChangeAdvisoryService.class);
        replayRequestRepository = mock(ReplayEnvironmentRequestRepository.class);
        replayRequestService = mock(ReplayEnvironmentRequestService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        ReplayLabProperties properties = new ReplayLabProperties();
        ReplayLabProperties.Target target = new ReplayLabProperties.Target();
        ReplayLabProperties.SourceCandidateRepository backend =
                new ReplayLabProperties.SourceCandidateRepository();
        backend.setProjectKey("DCE");
        backend.setRepositorySlug("backend");
        backend.setBranch("test2");
        target.getBitbucket().getRepositories().put("backend", backend);
        properties.getTargets().put("bss-monolith", target);

        service = new PatchPlanCandidateService(
                caseRepository,
                evidenceRepository,
                regressionTestDraftService,
                fixPlanService,
                advisoryService,
                replayRequestRepository,
                replayRequestService,
                properties
        );

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase()));
        when(regressionTestDraftService.draft(eq(caseId), anyBoolean(), anyInt()))
                .thenReturn(regressionDraft());
        when(fixPlanService.plan(eq(caseId), anyBoolean(), anyInt()))
                .thenReturn(fixPlan());
        when(advisoryService.summary(caseId)).thenReturn(advisorySummary());
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                eq(caseId),
                eq(EvidenceType.REPLAY_OUTPUT)
        )).thenReturn(List.of());
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                eq(caseId),
                eq(EvidenceType.GENERATED_TEST)
        )).thenReturn(List.of());
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                eq(caseId),
                eq(EvidenceType.APPLICATION_DB_EVIDENCE)
        )).thenReturn(List.of());
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                eq(caseId),
                eq(EvidenceType.JENKINS_RESULT)
        )).thenReturn(List.of());
        ReplayEnvironmentRequestEntity request =
                new ReplayEnvironmentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setCaseId(caseId);
        request.setJiraKey("FIZZMS-10228");
        request.setTargetKey("bss-monolith");
        request.setStatus("APPROVED");
        when(replayRequestRepository.findByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(List.of(request));
        when(replayRequestService.provisionReadiness(request.getId()))
                .thenReturn(readiness(request.getId()));
    }

    @Test
    void returnsHypothesisDraftAndNeverRecommendsPatchDirectly() {
        PatchPlanCandidateResponse response =
                service.candidate(caseId, false, true, true);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.patchPlanStatus()).isIn(
                "DRAFT",
                "NEEDS_MORE_EVIDENCE"
        );
        assertThat(response.shouldProceedToPatch()).isFalse();
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void includesExpectedSourceTargetsAndRecommendedChangeType() {
        PatchPlanCandidateResponse response =
                service.candidate(caseId, false, true, true);

        assertThat(response.targetEndpoint()).isEqualTo("/user/region/update");
        assertThat(response.targetClass()).isEqualTo("UserServiceImpl");
        assertThat(response.targetMethod()).isEqualTo("updateUser");
        assertThat(response.targetMethods())
                .contains("UserServiceImpl#updateUser");
        assertThat(response.recommendedChangeType())
                .isIn("VALIDATION_GUARD", "MAPPING_FIX");
        assertThat(response.recommendedCodeChange())
                .containsEntry("writesCode", false)
                .containsEntry("opensPullRequest", false)
                .containsEntry("requiresHumanApproval", true);
    }

    @Test
    void includesRegressionTestPlanAndDbValidationRequirements() {
        PatchPlanCandidateResponse response =
                service.candidate(caseId, false, true, true);

        String json = asJson(response);
        assertThat(json)
                .contains("API_INTEGRATION")
                .contains("SERVICE_UNIT")
                .contains("preferredProvince")
                .contains("region mismatch");
        assertThat(response.dbValidationRequirements()).contains(
                "USER_PREFERRED_PROVINCE",
                "USER_REGION_STATE",
                "CUSTOMER_ADDRESS_REGION",
                "TAX_INFO_STATE",
                "TIMEZONE_STATE",
                "BILLING_ACCOUNT_REGION"
        );
    }

    @Test
    void includesMissingEvidenceWhenReplayIsNotConfirmed() {
        PatchPlanCandidateResponse response =
                service.candidate(caseId, false, true, true);

        assertThat(response.missingEvidence()).contains(
                PatchPlanCandidateService.REPLAY_REPRODUCTION,
                PatchPlanCandidateService.FAILING_REGRESSION_TEST,
                PatchPlanCandidateService.APPLICATION_DB_EVIDENCE,
                PatchPlanCandidateService.REPLAY_ENVIRONMENT_PROVISIONING,
                PatchPlanCandidateService.JENKINS_VALIDATION
        );
    }

    @Test
    void doesNotExposeRawReasoningContentOrSensitiveValues() {
        PatchPlanCandidateResponse response =
                service.candidate(caseId, false, true, true);

        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("SECRET_REASONING")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("password")
                .doesNotContain("token");
    }

    @Test
    void doesNotWriteFilesCreateBranchesOpenPrsOrRunJenkins() {
        service.candidate(caseId, false, true, true);

        verify(fixPlanService).plan(eq(caseId), eq(false), anyInt());
        verify(regressionTestDraftService)
                .draft(eq(caseId), eq(false), anyInt());
        verify(advisoryService).summary(caseId);
        verify(caseRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.CONTEXT_READY);
        return entity;
    }

    private RegressionTestDraftResponse regressionDraft() {
        return new RegressionTestDraftResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                List.of("API_INTEGRATION", "SERVICE_UNIT"),
                "API_INTEGRATION",
                "/user/region/update",
                "UserServiceImpl",
                "updateUser",
                List.of(new RegressionTestScenario(
                        "preferredProvince / region mismatch scenario",
                        "API_INTEGRATION",
                        "/user/region/update",
                        "UserServiceImpl",
                        "updateUser",
                        List.of("Sanitized user input exists"),
                        "Call /user/region/update with preferredProvince and region mismatch",
                        "The API rejects, normalizes, or safely handles the mismatch.",
                        List.of(),
                        List.of(),
                        List.of("DRAFT_ONLY")
                )),
                List.of(),
                List.of(),
                List.of(
                        db("USER_PREFERRED_PROVINCE"),
                        db("USER_REGION_STATE"),
                        db("CUSTOMER_ADDRESS_REGION"),
                        db("TAX_INFO_STATE"),
                        db("TIMEZONE_STATE"),
                        db("BILLING_ACCOUNT_REGION")
                ),
                true,
                true,
                List.of()
        );
    }

    private RegressionTestDbValidationRequirement db(String id) {
        return new RegressionTestDbValidationRequirement(
                id,
                id,
                "Read-only validation",
                List.of("userId"),
                List.of(id),
                List.of("state")
        );
    }

    private FixPlanResponse fixPlan() {
        FixPlanCandidate candidate = new FixPlanCandidate(
                PatchRuleRegistry.VALIDATION_GUARD,
                "ControllerBackend/src/main/java/company/UserServiceImpl.java",
                "UserServiceImpl",
                "updateUser",
                "SERVICE_IMPL",
                "/user/region/update",
                List.of("preferredProvince", "region"),
                PatchRuleRegistry.VALIDATION_GUARD,
                "Validation guard",
                "Add validation before mutating user region state.",
                "MEDIUM",
                0.42,
                "HYPOTHESIS",
                true,
                List.of(),
                List.of()
        );
        return new FixPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                0.42,
                List.of(candidate),
                candidate,
                List.of(),
                List.of(FixPlanService.APPLICATION_DB_EVIDENCE),
                true,
                true,
                List.of()
        );
    }

    private CodeChangeAdvisoryEvaluationSummaryResponse advisorySummary() {
        CodeChangeAdvisoryResultSummary backend =
                new CodeChangeAdvisoryResultSummary(
                        UUID.randomUUID(),
                        "BACKEND_METHOD",
                        false,
                        "NOT_REQUESTED",
                        "HYPOTHESIS",
                        "0.4",
                        new RecommendedCodeChange(
                                "ControllerBackend/src/main/java/company/UserServiceImpl.java",
                                "updateUser",
                                "VALIDATION_GUARD",
                                "Validate region/preferred province consistency.",
                                ""
                        ),
                        List.of("Shared DB state must not be changed without approval."),
                        List.of("REPLAY_REPRODUCTION"),
                        List.of("Add API integration test."),
                        false,
                        "FALLBACK_NOT_REQUESTED",
                        Map.of(
                                "filePath",
                                "ControllerBackend/src/main/java/company/UserServiceImpl.java",
                                "classOrComponentName",
                                "UserServiceImpl",
                                "language",
                                "JAVA"
                        ),
                        Instant.now()
                );
        CodeChangeAdvisoryResultSummary risk =
                new CodeChangeAdvisoryResultSummary(
                        UUID.randomUUID(),
                        "RISK_REVIEW",
                        false,
                        "NOT_REQUESTED",
                        "HYPOTHESIS",
                        "0.3",
                        new RecommendedCodeChange(
                                "",
                                "",
                                "VALIDATION_GUARD",
                                "Review risk.",
                                ""
                        ),
                        List.of("Changing mapping may affect billing and tax state."),
                        List.of(),
                        List.of(),
                        false,
                        "FALLBACK_NOT_REQUESTED",
                        Map.of(),
                        Instant.now()
                );
        return new CodeChangeAdvisoryEvaluationSummaryResponse(
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                2,
                backend,
                null,
                null,
                risk,
                0.35,
                1,
                1,
                0,
                "ADVISORY_READY",
                Instant.now()
        );
    }

    private ReplayEnvironmentProvisionReadinessResponse readiness(UUID requestId) {
        return new ReplayEnvironmentProvisionReadinessResponse(
                requestId,
                caseId,
                "FIZZMS-10228",
                "bss-monolith",
                "APPROVED",
                "BLOCKED",
                true,
                false,
                true,
                true,
                false,
                true,
                true,
                false,
                true,
                true,
                true,
                false,
                "project-replay-sandbox",
                "replay-fizzms-10228.replay.fizz12.etiya.com",
                List.of("REAL_PROVISIONING_DISABLED"),
                List.of(),
                List.of("REAL_PROVISIONING_DISABLED"),
                List.of(),
                List.of("Configure provisioning gates"),
                List.of("dry-run first"),
                Instant.now()
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
