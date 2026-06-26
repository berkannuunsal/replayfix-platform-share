package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.ReplayLabEnvironmentBlueprintRequest;
import com.etiya.replaylab.api.dto.ReplayLabEnvironmentBlueprintResponse;
import com.etiya.replaylab.api.dto.ReplayLabEvidenceDetail;
import com.etiya.replaylab.api.dto.ReplayLabHumanEvidenceRequest;
import com.etiya.replaylab.api.dto.ReplayLabLiveDemoStateResponse;
import com.etiya.replaylab.api.dto.ReplayLabRcaResponse;
import com.etiya.replaylab.api.dto.ReplayLabTokenUsageEstimateResponse;
import com.etiya.replaylab.service.ReplayLabLiveDemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/live-demo")
public class ReplayLabLiveDemoController {

    private final ReplayLabLiveDemoService liveDemoService;

    public ReplayLabLiveDemoController(ReplayLabLiveDemoService liveDemoService) {
        this.liveDemoService = liveDemoService;
    }

    @GetMapping("/state")
    public ReplayLabLiveDemoStateResponse state(@PathVariable UUID caseId) {
        return liveDemoService.state(caseId);
    }

    @PostMapping("/start")
    public ReplayLabLiveDemoStateResponse start(@PathVariable UUID caseId) {
        return liveDemoService.start(caseId);
    }

    @PostMapping("/collect-evidence")
    public ReplayLabLiveDemoStateResponse collectEvidence(@PathVariable UUID caseId) {
        return liveDemoService.collectEvidence(caseId);
    }

    @PostMapping("/generate-rca")
    public ReplayLabRcaResponse generateRca(@PathVariable UUID caseId) {
        return liveDemoService.generateRca(caseId);
    }

    @PostMapping("/human-evidence")
    public ReplayLabLiveDemoStateResponse humanEvidence(
            @PathVariable UUID caseId,
            @RequestBody(required = false) ReplayLabHumanEvidenceRequest request
    ) {
        return liveDemoService.addHumanEvidence(caseId, request);
    }

    @GetMapping("/evidence")
    public List<ReplayLabEvidenceDetail> evidence(@PathVariable UUID caseId) {
        return liveDemoService.evidence(caseId);
    }

    @GetMapping("/token-usage")
    public ReplayLabTokenUsageEstimateResponse tokenUsage(@PathVariable UUID caseId) {
        return liveDemoService.tokenUsage(caseId);
    }

    @PostMapping("/environment/plan")
    public ReplayLabEnvironmentBlueprintResponse planEnvironment(
            @PathVariable UUID caseId,
            @RequestBody(required = false) ReplayLabEnvironmentBlueprintRequest request
    ) {
        return liveDemoService.planEnvironment(caseId, request);
    }

    @PostMapping("/environment/skip")
    public ReplayLabLiveDemoStateResponse skipEnvironment(@PathVariable UUID caseId) {
        return liveDemoService.skipEnvironment(caseId);
    }

    @GetMapping("/final-state")
    public ReplayLabLiveDemoStateResponse finalState(@PathVariable UUID caseId) {
        return liveDemoService.finalState(caseId);
    }
}
