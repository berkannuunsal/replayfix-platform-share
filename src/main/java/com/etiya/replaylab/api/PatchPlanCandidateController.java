package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.PatchPlanCandidateResponse;
import com.etiya.replaylab.service.PatchPlanCandidateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class PatchPlanCandidateController {

    private final PatchPlanCandidateService service;

    public PatchPlanCandidateController(PatchPlanCandidateService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/patch-plan-candidate")
    public PatchPlanCandidateResponse candidate(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean useCompanyLlm,
            @RequestParam(defaultValue = "true") boolean includeReplayReadiness,
            @RequestParam(defaultValue = "true") boolean includeRegressionDraft
    ) {
        return service.candidate(
                caseId,
                useCompanyLlm,
                includeReplayReadiness,
                includeRegressionDraft
        );
    }
}
