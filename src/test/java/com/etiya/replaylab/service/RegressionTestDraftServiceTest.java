package com.etiya.replaylab.service;

import com.etiya.replaylab.model.ApplicationDbEvidenceQueryTemplate;
import com.etiya.replaylab.model.ApplicationDbEvidenceResponse;
import com.etiya.replaylab.model.FixPlanCandidate;
import com.etiya.replaylab.model.FixPlanResponse;
import com.etiya.replaylab.model.RegressionTestDraftResponse;
import com.etiya.replaylab.model.SourceCandidateFlowChainItem;
import com.etiya.replaylab.model.SourceCandidateMethod;
import com.etiya.replaylab.model.SourceReasoningContext;
import com.etiya.replaylab.model.SourceSuspectChangeAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegressionTestDraftServiceTest {

    private UUID caseId;
    private SourceSuspectChangeAnalysisService sourceAnalysisService;
    private FixPlanService fixPlanService;
    private ApplicationDbEvidenceService dbEvidenceService;
    private RegressionTestDraftService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        sourceAnalysisService = mock(SourceSuspectChangeAnalysisService.class);
        fixPlanService = mock(FixPlanService.class);
        dbEvidenceService = mock(ApplicationDbEvidenceService.class);
        service = new RegressionTestDraftService(
                sourceAnalysisService,
                fixPlanService,
                dbEvidenceService
        );

        when(sourceAnalysisService.analyze(
                eq(caseId),
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
        )).thenReturn(sourceAnalysis("SUCCESS", true, List.of()));
        when(fixPlanService.plan(eq(caseId), anyBoolean(), anyInt()))
                .thenReturn(fixPlan());
        when(dbEvidenceService.collect(eq(caseId), anyString(), anyBoolean(), anyInt()))
                .thenReturn(dbEvidence());
    }

    @Test
    void createsDraftFromUserRegionUpdateSourceReasoning() {
        RegressionTestDraftResponse response = service.draft(caseId, false, 3);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.scenarios()).isNotEmpty();
        assertThat(response.scenarios().get(0).name())
                .contains("Region update rejects or normalizes");
    }

    @Test
    void includesEndpointServiceTargetAndApiIntegrationSelection() {
        RegressionTestDraftResponse response = service.draft(caseId, false, 3);

        assertThat(response.targetEndpoint()).isEqualTo("/user/region/update");
        assertThat(response.targetClass()).isEqualTo("UserServiceImpl");
        assertThat(response.targetMethod()).isEqualTo("updateUser");
        assertThat(response.selectedTestType()).isEqualTo("API_INTEGRATION");
        assertThat(response.testTypeCandidates())
                .contains("API_INTEGRATION", "SERVICE_UNIT");
    }

    @Test
    void scenarioCoversPreferredProvinceRegionMismatch() {
        RegressionTestDraftResponse response = service.draft(caseId, false, 3);
        String json = asJson(response);

        assertThat(json)
                .contains("preferredProvince")
                .contains("province-region")
                .contains("UpdateAplUserPrefPrvncRequest")
                .contains("API response should not silently accept invalid state")
                .contains("Service should not leave user profile in inconsistent region state");
    }

    @Test
    void includesDbValidationRequirementsWhenDbEvidenceIsRequired() {
        RegressionTestDraftResponse response = service.draft(caseId, false, 3);

        assertThat(response.requiresDbEvidence()).isTrue();
        assertThat(response.dbValidationRequirements())
                .extracting("templateId")
                .contains(
                        "USER_PREFERRED_PROVINCE",
                        "USER_REGION_STATE",
                        "CUSTOMER_ADDRESS_REGION",
                        "TAX_INFO_STATE",
                        "TIMEZONE_STATE",
                        "BILLING_ACCOUNT_REGION"
                );
    }

    @Test
    void includesDataAndMockRequirements() {
        RegressionTestDraftResponse response = service.draft(caseId, false, 3);

        assertThat(response.dataRequirements())
                .extracting("name")
                .contains(
                        "userId or customerId",
                        "original preferred province",
                        "requested preferred province",
                        "expected province-region mapping",
                        "expected tax/timezone/billing region state",
                        "sanitized request body sample"
                );
        assertThat(response.mockRequirements())
                .anySatisfy(requirement -> {
                    assertThat(requirement.target())
                            .contains("UserServiceImpl#updateUser");
                    assertThat(requirement.reason())
                            .contains("mock persistence collaborators");
                })
                .anySatisfy(requirement -> {
                    assertThat(requirement.reason())
                            .contains("Do not mock the method under test itself");
                });
    }

    @Test
    void alwaysRequiresHumanApprovalAndDoesNotClaimVerification() {
        RegressionTestDraftResponse response = service.draft(caseId, false, 3);
        String json = asJson(response);

        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(json)
                .doesNotContain("CONFIRMED")
                .doesNotContain("reproduced\":true")
                .doesNotContain("verified\":true")
                .doesNotContain("diff --git")
                .doesNotContain("generatedPatch")
                .doesNotContain("patchContent");
    }

    @Test
    void companyLlmUnavailableDoesNotBreakDeterministicDraft() {
        when(sourceAnalysisService.analyze(
                eq(caseId),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                eq(true),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenReturn(sourceAnalysis(
                "UNAVAILABLE",
                false,
                List.of(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE)
        ));

        RegressionTestDraftResponse response = service.draft(caseId, true, 3);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.scenarios()).isNotEmpty();
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
    }

    @Test
    void rawReasoningContentIsNotExposed() {
        when(sourceAnalysisService.analyze(
                eq(caseId),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                eq(true),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenReturn(sourceAnalysis(
                "ERROR",
                false,
                List.of(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE)
        ));

        RegressionTestDraftResponse response = service.draft(caseId, true, 3);

        assertThat(asJson(response))
                .doesNotContain("reasoning_content")
                .doesNotContain("SECRET_REASONING");
        assertThat(response.warnings())
                .contains(CompanySourceReasoningService
                        .COMPANY_LLM_INVALID_RESPONSE);
    }

    private FixPlanResponse fixPlan() {
        FixPlanCandidate candidate = new FixPlanCandidate(
                PatchRuleRegistry.VALIDATION_GUARD,
                "CrmBackend/src/main/java/UserServiceImpl.java",
                "UserServiceImpl",
                "updateUser",
                "SERVICE_IMPL",
                "/user/region/update",
                List.of("/user/region/update", "preferredProvince"),
                PatchRuleRegistry.VALIDATION_GUARD,
                "Validation guard",
                "Plan only; no patch is generated.",
                "MEDIUM",
                0.4,
                "HYPOTHESIS",
                true,
                List.of(),
                List.of()
        );
        return new FixPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                0.4,
                List.of(candidate),
                candidate,
                List.of(),
                List.of(FixPlanService.APPLICATION_DB_EVIDENCE),
                true,
                true,
                List.of()
        );
    }

    private SourceSuspectChangeAnalysisResponse sourceAnalysis(
            String companyLlmStatus,
            boolean llmUsed,
            List<String> warnings
    ) {
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
                List.of(new SourceCandidateMethod(
                        "CrmBackend/src/main/java/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        10,
                        20,
                        List.of("/user/region/update", "preferredProvince"),
                        "partyEntity.setPrefPrvncId(stateEntity.getStateId().intValue());"
                )),
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
                llmUsed,
                List.of(),
                "HYPOTHESIS",
                0.5,
                warnings,
                llmUsed ? "COMPANY_LLM" : "DETERMINISTIC_ONLY",
                false,
                Map.of(),
                "companyLlm",
                null,
                1,
                1,
                1,
                List.of("/user/region/update"),
                List.of(),
                List.of(),
                1,
                List.of("UserService"),
                List.of("CrmBackend/src/main/java/UserServiceImpl.java"),
                List.of(),
                List.of(),
                45,
                100,
                companyLlmStatus,
                2500,
                "MINIMAL",
                12000,
                3000,
                "abc",
                null,
                "",
                3000,
                Map.of()
        );
    }

    private ApplicationDbEvidenceResponse dbEvidence() {
        return new ApplicationDbEvidenceResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                "backend",
                true,
                List.of(
                        template("USER_PREFERRED_PROVINCE"),
                        template("USER_REGION_STATE"),
                        template("CUSTOMER_ADDRESS_REGION"),
                        template("TAX_INFO_STATE"),
                        template("TIMEZONE_STATE"),
                        template("BILLING_ACCOUNT_REGION")
                ),
                List.of(),
                List.of(),
                true,
                List.of(ApplicationDbEvidenceService.DB_EVIDENCE_DRY_RUN)
        );
    }

    private ApplicationDbEvidenceQueryTemplate template(String id) {
        return new ApplicationDbEvidenceQueryTemplate(
                id,
                id.replace('_', ' '),
                "Read-only validation for " + id,
                List.of("userId"),
                List.of("APP_TABLE"),
                List.of("ID", "STATE"),
                "SELECT ID, STATE FROM APP_TABLE WHERE USER_ID = :userId",
                List.of("ID"),
                true
        );
    }

    private String asJson(Object value) {
        try {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
