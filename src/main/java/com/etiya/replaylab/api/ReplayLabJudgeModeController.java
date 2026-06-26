package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.ReplayLabDoraImpactScoreboardResponse;
import com.etiya.replaylab.api.dto.ReplayLabFinalRemediationBriefResponse;
import com.etiya.replaylab.api.dto.ReplayLabJudgeModeStartRequest;
import com.etiya.replaylab.api.dto.ReplayLabJudgeModeStartResponse;
import com.etiya.replaylab.api.dto.ReplayLabRemediationReadinessResponse;
import com.etiya.replaylab.service.ReplayLabDoraImpactScoreboardService;
import com.etiya.replaylab.service.ReplayLabFinalRemediationBriefService;
import com.etiya.replaylab.service.ReplayLabJudgeModeService;
import com.etiya.replaylab.service.ReplayLabRemediationReadinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ReplayLabJudgeModeController {

    private final ReplayLabDoraImpactScoreboardService doraImpactScoreboardService;
    private final ReplayLabRemediationReadinessService remediationReadinessService;
    private final ReplayLabFinalRemediationBriefService finalRemediationBriefService;
    private final ReplayLabJudgeModeService judgeModeService;

    public ReplayLabJudgeModeController(
            ReplayLabDoraImpactScoreboardService doraImpactScoreboardService,
            ReplayLabRemediationReadinessService remediationReadinessService,
            ReplayLabFinalRemediationBriefService finalRemediationBriefService,
            ReplayLabJudgeModeService judgeModeService
    ) {
        this.doraImpactScoreboardService = doraImpactScoreboardService;
        this.remediationReadinessService = remediationReadinessService;
        this.finalRemediationBriefService = finalRemediationBriefService;
        this.judgeModeService = judgeModeService;
    }

    @GetMapping("/cases/{caseId}/dora-impact-scoreboard")
    public ReplayLabDoraImpactScoreboardResponse doraImpactScoreboard(
            @PathVariable UUID caseId
    ) {
        return doraImpactScoreboardService.scoreboard(caseId);
    }

    @GetMapping("/cases/{caseId}/remediation-readiness")
    public ReplayLabRemediationReadinessResponse remediationReadiness(
            @PathVariable UUID caseId
    ) {
        return remediationReadinessService.readiness(caseId);
    }

    @GetMapping("/cases/{caseId}/final-remediation-brief")
    public ReplayLabFinalRemediationBriefResponse finalRemediationBrief(
            @PathVariable UUID caseId
    ) {
        return finalRemediationBriefService.brief(caseId);
    }

    @PostMapping("/demo/replaylab/judge-mode/start")
    public ReplayLabJudgeModeStartResponse startJudgeMode(
            @RequestBody(required = false) ReplayLabJudgeModeStartRequest request
    ) {
        return judgeModeService.start(request);
    }
}
