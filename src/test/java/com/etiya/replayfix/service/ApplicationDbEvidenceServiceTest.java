package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.ApplicationDbEvidenceResponse;
import com.etiya.replayfix.model.FixPlanCandidate;
import com.etiya.replayfix.model.FixPlanResponse;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceCandidateMethod;
import com.etiya.replayfix.model.SourceReasoningContext;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class ApplicationDbEvidenceServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private FixPlanService fixPlanService;
    private SourceSuspectChangeAnalysisService sourceAnalysisService;
    private ApplicationDbEvidenceService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        fixPlanService = mock(FixPlanService.class);
        sourceAnalysisService = mock(SourceSuspectChangeAnalysisService.class);
        service = new ApplicationDbEvidenceService(
                caseRepository,
                fixPlanService,
                sourceAnalysisService,
                new ApplicationDbEvidenceQueryRegistry(new SqlReadOnlyGuard()),
                new SqlReadOnlyGuard(),
                new ReplayFixProperties()
        );

        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase()));
        when(fixPlanService.plan(any(), anyBoolean(), anyInt()))
                .thenReturn(fixPlan());
        when(sourceAnalysisService.analyze(
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean()
        )).thenReturn(sourceAnalysis());
    }

    @Test
    void executeFalseReturnsTemplatesWithoutDatasource() {
        ApplicationDbEvidenceResponse response = service.collect(
                caseId,
                "backend",
                false,
                20
        );

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.readOnly()).isTrue();
        assertThat(response.masked()).isTrue();
        assertThat(response.executedQueries()).isEmpty();
        assertThat(response.warnings())
                .contains(
                        ApplicationDbEvidenceService.DB_EVIDENCE_DRY_RUN,
                        ApplicationDbEvidenceService
                                .APPLICATION_DB_DATASOURCE_NOT_CONFIGURED
                );
        assertThat(response.queryTemplates())
                .extracting("templateId")
                .contains(
                        ApplicationDbEvidenceQueryRegistry.USER_PREFERRED_PROVINCE,
                        ApplicationDbEvidenceQueryRegistry.USER_REGION_STATE,
                        ApplicationDbEvidenceQueryRegistry.TAX_INFO_STATE,
                        ApplicationDbEvidenceQueryRegistry.TIMEZONE_STATE,
                        ApplicationDbEvidenceQueryRegistry.BILLING_ACCOUNT_REGION
                );
    }

    @Test
    void executeFalseDoesNotRequireDbConnection() {
        ApplicationDbEvidenceResponse response = service.collect(
                caseId,
                "backend",
                false,
                20
        );

        assertThat(response.executedQueries()).isEmpty();
        assertThat(response.warnings())
                .doesNotContain(ApplicationDbEvidenceService
                        .DB_EVIDENCE_QUERY_FAILED);
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        return replayCase;
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
                "Plan only.",
                "MEDIUM",
                0.3,
                "HYPOTHESIS",
                true,
                List.of(),
                List.of()
        );
        return new FixPlanResponse(
                caseId,
                "FIZZMS-10228",
                "HYPOTHESIS",
                0.3,
                List.of(candidate),
                candidate,
                List.of(),
                List.of(FixPlanService.APPLICATION_DB_EVIDENCE),
                true,
                true,
                List.of()
        );
    }

    private SourceSuspectChangeAnalysisResponse sourceAnalysis() {
        return new SourceSuspectChangeAnalysisResponse(
                caseId,
                "FIZZMS-10228",
                "DCE/backend",
                "test2",
                "",
                45,
                List.of(),
                List.of(new SourceCandidateFlowChainItem(
                        "SERVICE_IMPL",
                        "CrmBackend/src/main/java/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        List.of("/user/region/update", "billingAccount"),
                        "service chain",
                        "HYPOTHESIS"
                )),
                List.of(),
                List.of(new SourceCandidateMethod(
                        "CrmBackend/src/main/java/UserServiceImpl.java",
                        "UserServiceImpl",
                        "updateUser",
                        1,
                        10,
                        List.of("/user/region/update"),
                        "preferredProvince taxInfo TimeZone billingAccount"
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
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(),
                "DETERMINISTIC_ONLY",
                true
        );
    }
}
