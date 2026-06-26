package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabFinalRemediationBriefResponse;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayLabFinalRemediationBriefServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ReplayLabFinalRemediationBriefService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        ReplayLabDoraImpactScoreboardService doraService =
                new ReplayLabDoraImpactScoreboardService(caseRepository);
        ReplayLabRemediationReadinessService readinessService =
                new ReplayLabRemediationReadinessService(caseRepository, evidenceRepository);
        service = new ReplayLabFinalRemediationBriefService(
                caseRepository,
                evidenceRepository,
                doraService,
                readinessService
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());
    }

    @Test
    void finalBriefIncludesDefectCaseDoraImpactAndGuardrails() {
        ReplayLabFinalRemediationBriefResponse response = service.brief(caseId);

        assertThat(response.markdown())
                .contains("## ReplayLab Final Remediation Brief")
                .contains("FIZZMS-10228")
                .contains(caseId.toString())
                .contains("### DORA Impact")
                .contains("Estimated diagnosis reduction: 90%")
                .contains("### Guardrails")
                .contains("merge a PR")
                .contains("push directly to the target branch");
        assertThat(response.remediationReadiness().score()).isEqualTo(82);
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
