package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabJudgeModeStartRequest;
import com.etiya.replaylab.api.dto.ReplayLabJudgeModeStartResponse;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayLabJudgeModeServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ReplayLabJudgeModeService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        ReplayLabDoraImpactScoreboardService doraService =
                new ReplayLabDoraImpactScoreboardService(caseRepository);
        ReplayLabRemediationReadinessService readinessService =
                new ReplayLabRemediationReadinessService(caseRepository, evidenceRepository);
        ReplayLabFinalRemediationBriefService briefService =
                new ReplayLabFinalRemediationBriefService(
                        caseRepository,
                        evidenceRepository,
                        doraService,
                        readinessService
                );
        service = new ReplayLabJudgeModeService(
                caseRepository,
                evidenceRepository,
                doraService,
                readinessService,
                briefService,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void judgeModeReusesExistingCaseAndPerformsNoExternalWriteAction() {
        ReplayCaseEntity entity = caseEntity();
        when(caseRepository.findFirstByJiraKeyAndTargetKey("FIZZMS-10228", "backend"))
                .thenReturn(Optional.of(entity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());

        ReplayLabJudgeModeStartResponse response = service.start(request());

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.guardrails())
                .contains(
                        "No write action executed",
                        "No PR created",
                        "No Jenkins trigger executed",
                        "No Jira comment published"
                );
        verify(caseRepository, never()).save(any());
    }

    @Test
    void judgeModeCreatesCaseWhenMissing() {
        when(caseRepository.findFirstByJiraKeyAndTargetKey("FIZZMS-10228", "backend"))
                .thenReturn(Optional.empty());
        when(caseRepository.save(any(ReplayCaseEntity.class))).thenAnswer(invocation -> {
            ReplayCaseEntity entity = invocation.getArgument(0);
            entity.setId(caseId);
            return entity;
        });
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());

        ReplayLabJudgeModeStartResponse response = service.start(request());

        assertThat(response.caseId()).isEqualTo(caseId);
        verify(caseRepository).save(any(ReplayCaseEntity.class));
    }

    @Test
    void judgeModeResponseIncludesDoraReadinessAndFinalBrief() throws Exception {
        ReplayCaseEntity entity = caseEntity();
        when(caseRepository.findFirstByJiraKeyAndTargetKey("FIZZMS-10228", "backend"))
                .thenReturn(Optional.of(entity));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());

        ReplayLabJudgeModeStartResponse response = service.start(request());

        assertThat(response.doraImpact().measurementType()).isEqualTo("DEMO_BENCHMARK_ESTIMATE");
        assertThat(response.remediationReadiness().verdict()).isEqualTo("READY_FOR_HUMAN_REVIEW");
        assertThat(response.finalBriefMarkdown()).contains("ReplayLab Final Remediation Brief");

        String serialized = new ObjectMapper().writeValueAsString(response).toLowerCase();
        assertThat(serialized)
                .doesNotContain("authorization")
                .doesNotContain("token")
                .doesNotContain("secret");
    }

    private ReplayLabJudgeModeStartRequest request() {
        return new ReplayLabJudgeModeStartRequest(
                "berkan",
                "FIZZMS-10228",
                "backend",
                "test2",
                true
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("backend");
        entity.setEnvironment("test2");
        entity.setSynthetic(true);
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
