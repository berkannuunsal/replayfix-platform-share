package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.ApproveReplayEnvironmentRequest;
import com.etiya.replayfix.api.dto.CreateReplayEnvironmentRequestResponse;
import com.etiya.replayfix.api.dto.RejectReplayEnvironmentRequest;
import com.etiya.replayfix.api.dto.ReplayEnvironmentDemoSummaryResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentLlmAdvisoryRequest;
import com.etiya.replayfix.api.dto.ReplayEnvironmentLlmAdvisoryResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisioningDisabledResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentRequestResponse;
import com.etiya.replayfix.service.ReplayEnvironmentLlmAdvisoryService;
import com.etiya.replayfix.service.ReplayEnvironmentRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ReplayEnvironmentRequestController {

    private static final String REAL_PROVISIONING_DRY_RUN_MESSAGE =
            "Real ArgoCD provisioning is not enabled yet. Use dryRun=true.";

    private final ReplayEnvironmentRequestService requestService;
    private final ReplayEnvironmentLlmAdvisoryService advisoryService;

    public ReplayEnvironmentRequestController(
            ReplayEnvironmentRequestService requestService,
            ReplayEnvironmentLlmAdvisoryService advisoryService
    ) {
        this.requestService = requestService;
        this.advisoryService = advisoryService;
    }

    @PostMapping("/cases/{caseId}/replay-environment/requests")
    public ResponseEntity<?> create(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean includeCustomerUi,
            @RequestParam(defaultValue = "WIREMOCK") String mockMode,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) String requestedBy
    ) {
        if (!dryRun) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", REAL_PROVISIONING_DRY_RUN_MESSAGE));
        }
        CreateReplayEnvironmentRequestResponse response =
                requestService.create(
                        caseId,
                        includeCustomerUi,
                        mockMode,
                        requestedBy
                );
        return ResponseEntity.created(URI.create(
                        "/api/v1/replay-environment/requests/"
                                + response.request().requestId()
                ))
                .body(response);
    }

    @GetMapping("/replay-environment/requests/{requestId}")
    public ReplayEnvironmentRequestResponse get(
            @PathVariable UUID requestId
    ) {
        return requestService.get(requestId);
    }

    @GetMapping("/replay-environment/requests/{requestId}/plan")
    public ReplayEnvironmentPlanResponse getPlan(
            @PathVariable UUID requestId
    ) {
        return requestService.getPlan(requestId);
    }

    @GetMapping("/replay-environment/requests/{requestId}/provision-readiness")
    public ReplayEnvironmentProvisionReadinessResponse provisionReadiness(
            @PathVariable UUID requestId
    ) {
        return requestService.provisionReadiness(requestId);
    }

    @GetMapping("/replay-environment/requests/{requestId}/demo-summary")
    public ReplayEnvironmentDemoSummaryResponse demoSummary(
            @PathVariable UUID requestId
    ) {
        return requestService.demoSummary(requestId);
    }

    @PostMapping("/replay-environment/requests/{requestId}/llm-advisory")
    public ReplayEnvironmentLlmAdvisoryResponse llmAdvisory(
            @PathVariable UUID requestId,
            @RequestParam(defaultValue = "ARCHITECTURE_REVIEW")
            String advisoryMode,
            @RequestParam(defaultValue = "true") boolean useCompanyLlm,
            @RequestBody(required = false)
            ReplayEnvironmentLlmAdvisoryRequest request
    ) {
        return advisoryService.advise(
                requestId,
                advisoryMode,
                useCompanyLlm,
                request
        );
    }

    @PostMapping("/replay-environment/requests/{requestId}/approve")
    public ReplayEnvironmentRequestResponse approve(
            @PathVariable UUID requestId,
            @RequestBody ApproveReplayEnvironmentRequest request
    ) {
        return requestService.approve(requestId, request);
    }

    @PostMapping("/replay-environment/requests/{requestId}/reject")
    public ReplayEnvironmentRequestResponse reject(
            @PathVariable UUID requestId,
            @RequestBody RejectReplayEnvironmentRequest request
    ) {
        return requestService.reject(requestId, request);
    }

    @PostMapping("/replay-environment/requests/{requestId}/provision")
    public ResponseEntity<ReplayEnvironmentProvisioningDisabledResponse> provision(
            @PathVariable UUID requestId
    ) {
        return ResponseEntity.status(409)
                .body(requestService.provision(requestId));
    }
}
