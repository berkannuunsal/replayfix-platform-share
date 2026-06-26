package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabDoraImpactScoreboardResponse;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayLabDoraImpactScoreboardServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private ReplayLabDoraImpactScoreboardService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        service = new ReplayLabDoraImpactScoreboardService(caseRepository);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void doraScoreboardReturnsDemoBenchmarkValues() {
        ReplayLabDoraImpactScoreboardResponse response = service.scoreboard(caseId);

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.defectKey()).isEqualTo("FIZZMS-10228");
        assertThat(response.primaryMetric()).isEqualTo("Mean Time to Recovery");
        assertThat(response.measurementType()).isEqualTo("DEMO_BENCHMARK_ESTIMATE");
        assertThat(response.scoreboard()).hasSize(4);
        assertThat(response.warnings())
                .contains("Benchmark values are demo estimates and must be replaced with production measurements after rollout.");
    }

    @Test
    void reductionPercentagesAreCalculatedCorrectly() {
        ReplayLabDoraImpactScoreboardResponse response = service.scoreboard(caseId);

        assertThat(response.scoreboard())
                .extracting(ReplayLabDoraImpactScoreboardResponse.ScoreboardItem::reductionPercent)
                .containsExactly(90, 95, 89, 93);
        assertThat(ReplayLabDoraImpactScoreboardService.reductionPercent(45, 5))
                .isEqualTo(89);
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("backend");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
