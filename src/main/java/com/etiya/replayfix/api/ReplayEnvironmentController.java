package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replayfix.service.ReplayEnvironmentPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class ReplayEnvironmentController {

    private static final String REAL_PROVISIONING_DISABLED =
            "Real ArgoCD provisioning is not enabled yet. Use dryRun=true.";

    private final ReplayEnvironmentPlanService planService;

    public ReplayEnvironmentController(
            ReplayEnvironmentPlanService planService
    ) {
        this.planService = planService;
    }

    @GetMapping("/{caseId}/replay-environment/plan")
    public ResponseEntity<?> plan(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean includeCustomerUi,
            @RequestParam(defaultValue = "WIREMOCK") String mockMode,
            @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        if (!dryRun) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", REAL_PROVISIONING_DISABLED));
        }
        ReplayEnvironmentPlanResponse response = planService.plan(
                caseId,
                includeCustomerUi,
                mockMode
        );
        return ResponseEntity.ok(response);
    }
}
