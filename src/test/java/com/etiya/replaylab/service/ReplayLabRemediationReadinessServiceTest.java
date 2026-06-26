package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabRemediationReadinessResponse;
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

class ReplayLabRemediationReadinessServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ReplayLabRemediationReadinessService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        service = new ReplayLabRemediationReadinessService(caseRepository, evidenceRepository);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity(true)));
        when(evidenceRepository.findByCaseId(caseId)).thenReturn(List.of());
    }

    @Test
    void readinessScoreReturnsReadyForHumanReviewForDemoBaseline() {
        ReplayLabRemediationReadinessResponse response = service.readiness(caseId);

        assertThat(response.score()).isEqualTo(82);
        assertThat(response.verdict()).isEqualTo("READY_FOR_HUMAN_REVIEW");
        assertThat(response.scoreBreakdown()).hasSize(5);
    }

    @Test
    void readinessResponseIncludesGuardrails() {
        ReplayLabRemediationReadinessResponse response = service.readiness(caseId);

        assertThat(response.guardrails())
                .contains(
                        "No automatic PR merge",
                        "No automatic deployment",
                        "No direct push to target branch",
                        "Human approval required for write actions"
                );
    }

    @Test
    void verdictBoundariesMatchReadinessRules() {
        assertThat(ReplayLabRemediationReadinessService.verdict(39)).isEqualTo("NOT_READY");
        assertThat(ReplayLabRemediationReadinessService.verdict(40)).isEqualTo("NEEDS_MORE_EVIDENCE");
        assertThat(ReplayLabRemediationReadinessService.verdict(70)).isEqualTo("READY_FOR_HUMAN_REVIEW");
        assertThat(ReplayLabRemediationReadinessService.verdict(90)).isEqualTo("READY_FOR_VALIDATION");
    }

    private ReplayCaseEntity caseEntity(boolean synthetic) {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("backend");
        entity.setSynthetic(synthetic);
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
