package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabDoraImpactScoreboardResponse;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ReplayLabDoraImpactScoreboardService {

    public static final String MEASUREMENT_TYPE = "DEMO_BENCHMARK_ESTIMATE";

    private final ReplayCaseRepository caseRepository;

    public ReplayLabDoraImpactScoreboardService(ReplayCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Transactional(readOnly = true)
    public ReplayLabDoraImpactScoreboardResponse scoreboard(UUID caseId) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId));
        return response(replayCase);
    }

    public ReplayLabDoraImpactScoreboardResponse response(ReplayCaseEntity replayCase) {
        return new ReplayLabDoraImpactScoreboardResponse(
                replayCase.getId(),
                safe(replayCase.getJiraKey()),
                "Mean Time to Recovery",
                List.of("Lead Time for Changes", "Change Failure Rate"),
                List.of(
                        item(
                                "Incident context collection",
                                60,
                                6,
                                "ReplayLab Golden Path collects Jira, Jenkins, Bitbucket, Loki, Tempo and source context into one case."
                        ),
                        item(
                                "Incident version validation",
                                20,
                                1,
                                "ReplayLab compares Jenkins commit SHA with source checkout commit SHA."
                        ),
                        item(
                                "RCA preparation",
                                45,
                                5,
                                "ReplayLab generates deterministic RCA and preserves confidence boundaries."
                        ),
                        item(
                                "PR rule preflight",
                                30,
                                2,
                                "ReplayLab loads AGENTS.md rules and runs blocker preflight."
                        )
                ),
                90,
                MEASUREMENT_TYPE,
                List.of("Benchmark values are demo estimates and must be replaced with production measurements after rollout.")
        );
    }

    static int reductionPercent(int beforeMinutes, int afterMinutes) {
        if (beforeMinutes <= 0) {
            return 0;
        }
        return (int) Math.round(((double) beforeMinutes - afterMinutes) * 100 / beforeMinutes);
    }

    private ReplayLabDoraImpactScoreboardResponse.ScoreboardItem item(
            String activity,
            int beforeMinutes,
            int afterMinutes,
            String evidence
    ) {
        return new ReplayLabDoraImpactScoreboardResponse.ScoreboardItem(
                activity,
                beforeMinutes,
                afterMinutes,
                reductionPercent(beforeMinutes, afterMinutes),
                evidence
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
