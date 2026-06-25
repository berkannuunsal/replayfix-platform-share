package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.ApprovedWritePlanResponse;
import com.etiya.replaylab.service.ApprovedWritePlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class ApprovedWritePlanController {

    private final ApprovedWritePlanService service;

    public ApprovedWritePlanController(ApprovedWritePlanService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/approved-write-plan")
    public ApprovedWritePlanResponse plan(
            @PathVariable UUID caseId,
            @RequestParam(required = false) String patchPlanId,
            @RequestParam(defaultValue = "true") boolean includeTestDraft,
            @RequestParam(defaultValue = "true") boolean includeFixDraft,
            @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        return service.plan(
                caseId,
                patchPlanId,
                includeTestDraft,
                includeFixDraft,
                dryRun
        );
    }
}
