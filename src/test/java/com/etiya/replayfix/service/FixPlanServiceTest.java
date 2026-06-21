package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.FixPlanResponse;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceCandidateMethod;
import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixPlanServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private SourceSuspectChangeAnalysisService sourceAnalysisService;
    private FixPlanService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        sourceAnalysisService = mock(SourceSuspectChangeAnalysisService.class);
        service = new FixPlanService(
                caseRepository,
                evidenceRepository,
                sourceAnalysisService,
                new PatchRuleRegistry()
        );
        objectMapper = new ObjectMapper().findAndRegisterModules();

        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(replayCase()));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());
        when(sourceAnalysisService.analyze(
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenReturn(sourceAnalysis());
    }

    @Test
    void createsFixPlanFromControllerServiceCandidateChain() {
        FixPlanResponse response = service.plan(caseId, false, 5);

        assertThat(response.fixCandidates()).isNotEmpty();
        assertThat(response.selectedCandidate()).isNotNull();
        assertThat(response.selectedCandidate().targetClass())
                .isEqualTo("UserServiceImpl");
        assertThat(response.selectedCandidate().targetMethod())
                .isEqualTo("updateUser");
    }

    @Test
    void prefersServiceImplementationOverController() {
        FixPlanResponse response = service.plan(caseId, false, 5);

        assertThat(response.selectedCandidate().targetLayer())
                .isEqualTo("SERVICE_IMPL");
        assertThat(response.selectedCandidate().targetClass())
                .isNotEqualTo("UserController");
    }

    @Test
    void mapsUserRegionUpdateToValidationGuardOrMappingFix() {
        FixPlanResponse response = service.plan(caseId, false, 5);

        assertThat(response.selectedCandidate().fixType())
                .isIn(PatchRuleRegistry.VALIDATION_GUARD,
                        PatchRuleRegistry.MAPPING_FIX);
        assertThat(response.fixCandidates())
                .extracting("fixType")
                .contains(PatchRuleRegistry.MAPPING_FIX);
    }

    @Test
    void marksDatabaseEvidenceRequiredForRegionTaxTimezoneCases() {
        FixPlanResponse response = service.plan(caseId, false, 5);

        assertThat(response.requiresDbEvidence()).isTrue();
        assertThat(response.missingEvidence())
                .contains(FixPlanService.APPLICATION_DB_EVIDENCE);
    }

    @Test
    void alwaysReturnsHypothesisAndRequiresHumanApproval() {
        FixPlanResponse response = service.plan(caseId, false, 5);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.selectedCandidate().status())
                .isEqualTo("HYPOTHESIS");
        assertThat(response.selectedCandidate().approvalRequired()).isTrue();
    }

    @Test
    void noPatchContentIsWrittenOrGenerated() throws Exception {
        FixPlanResponse response = service.plan(caseId, false, 5);

        String json = objectMapper.writeValueAsString(response);
        assertThat(json)
                .doesNotContain("diff --git")
                .doesNotContain("patchContent")
                .doesNotContain("generatedPatch");
    }

    @Test
    void companyLlmUnavailableDoesNotFailFixPlan() {
        when(sourceAnalysisService.analyze(
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenReturn(sourceAnalysisWithCompanyUnavailable());

        FixPlanResponse response = service.plan(caseId, true, 5);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.fixCandidates()).isNotEmpty();
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
    }

    @Test
    void canReferenceDbEvidenceIfPresent() {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(EvidenceType.APPLICATION_DB_EVIDENCE);
        evidence.setSource("application-db");
        evidence.setContentText("{\"finding\":\"preferred province read-only evidence\"}");
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        when(evidenceRepository.findByCaseId(caseId))
                .thenReturn(List.of(evidence));

        FixPlanResponse response = service.plan(caseId, false, 5);

        assertThat(response.requiresDbEvidence()).isFalse();
        assertThat(response.missingEvidence())
                .doesNotContain(FixPlanService.APPLICATION_DB_EVIDENCE);
        assertThat(response.requiredEvidence())
                .anySatisfy(reference -> {
                    assertThat(reference.evidenceType())
                            .isEqualTo(FixPlanService.APPLICATION_DB_EVIDENCE);
                    assertThat(reference.source()).isEqualTo("application-db");
                });
        assertThat(response.confidence()).isGreaterThan(0.4);
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setSourceBranch("test2");
        replayCase.setSourceCommit("abc123");
        return replayCase;
    }

    private SourceSuspectChangeAnalysisResponse sourceAnalysis() {
        return new SourceSuspectChangeAnalysisResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                "test2",
                "abc123",
                45,
                List.of(),
                List.of(
                        new SourceCandidateFlowChainItem(
                                "CONTROLLER",
                                "ControllerBackend/src/main/java/UserController.java",
                                "UserController",
                                "updateUserParty",
                                List.of("/user/region/update"),
                                "Controller endpoint mapping matched.",
                                "HYPOTHESIS"
                        ),
                        new SourceCandidateFlowChainItem(
                                "SERVICE_IMPL",
                                "CrmBackend/src/main/java/UserServiceImpl.java",
                                "UserServiceImpl",
                                "updateUser",
                                List.of("/user/region/update"),
                                "Service implementation method matched controller call.",
                                "HYPOTHESIS"
                        ),
                        new SourceCandidateFlowChainItem(
                                "DTO",
                                "BaseBackend/src/main/java/UpdateAplUserPrefPrvncRequest.java",
                                "UpdateAplUserPrefPrvncRequest",
                                "",
                                List.of("preferredProvince", "taxInfo", "TimeZone"),
                                "Request DTO referenced by service method.",
                                "HYPOTHESIS"
                        )
                ),
                List.of(),
                List.of(
                        new SourceCandidateMethod(
                                "CrmBackend/src/main/java/UserServiceImpl.java",
                                "UserServiceImpl",
                                "updateUser",
                                10,
                                20,
                                List.of("/user/region/update"),
                                "partyEntity.setPrefPrvncId(stateEntity.getStateId().intValue());"
                        )
                ),
                List.of(),
                new SourceReasoningContext(
                        Map.of(),
                        Map.of(),
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                ),
                false,
                List.of(),
                "HYPOTHESIS",
                0.25,
                List.of(SourceSuspectChangeAnalysisService.NO_RECENT_COMMITS_FOUND),
                "DETERMINISTIC_ONLY",
                true
        );
    }

    private SourceSuspectChangeAnalysisResponse sourceAnalysisWithCompanyUnavailable() {
        SourceSuspectChangeAnalysisResponse base = sourceAnalysis();
        return new SourceSuspectChangeAnalysisResponse(
                base.caseId(),
                base.jiraKey(),
                base.repository(),
                base.branch(),
                base.incidentCommitSha(),
                base.lookbackDays(),
                base.flowAnchors(),
                base.candidateFlowChain(),
                base.candidateFiles(),
                base.candidateMethods(),
                base.recentCommits(),
                base.sourceReasoningContext(),
                false,
                base.suspectChanges(),
                "HYPOTHESIS",
                base.confidence(),
                List.of(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE),
                "DETERMINISTIC_ONLY",
                true
        );
    }
}
