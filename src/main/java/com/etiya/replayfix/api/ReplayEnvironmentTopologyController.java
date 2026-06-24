package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.ReplayEnvironmentComponentHintsRequest;
import com.etiya.replayfix.api.dto.ReplayEnvironmentComponentHintsResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentTopologyPlanResponse;
import com.etiya.replayfix.service.ReplayEnvironmentComponentHintService;
import com.etiya.replayfix.service.ReplayEnvironmentTopologyPlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class ReplayEnvironmentTopologyController {

    private final ReplayEnvironmentComponentHintService hintService;
    private final ReplayEnvironmentTopologyPlanService topologyPlanService;

    public ReplayEnvironmentTopologyController(
            ReplayEnvironmentComponentHintService hintService,
            ReplayEnvironmentTopologyPlanService topologyPlanService
    ) {
        this.hintService = hintService;
        this.topologyPlanService = topologyPlanService;
    }

    @PostMapping("/{caseId}/replay-environment/component-hints")
    public ReplayEnvironmentComponentHintsResponse addComponentHints(
            @PathVariable UUID caseId,
            @RequestBody(required = false)
            ReplayEnvironmentComponentHintsRequest request
    ) {
        return hintService.addHints(caseId, request);
    }

    @GetMapping("/{caseId}/replay-environment/topology-plan")
    public ReplayEnvironmentTopologyPlanResponse topologyPlan(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean includeUserHints,
            @RequestParam(defaultValue = "true") boolean includeSourceReasoning,
            @RequestParam(defaultValue = "true") boolean includeDefaultBackend
    ) {
        return topologyPlanService.plan(
                caseId,
                includeUserHints,
                includeSourceReasoning,
                includeDefaultBackend
        );
    }
}
