package com.etiya.replaylab.api;

import com.etiya.replaylab.model.FixPlanResponse;
import com.etiya.replaylab.service.FixPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class FixPlanController {

    private static final Logger log = LoggerFactory.getLogger(
            FixPlanController.class
    );

    private final FixPlanService fixPlanService;

    public FixPlanController(FixPlanService fixPlanService) {
        this.fixPlanService = fixPlanService;
    }

    @GetMapping("/{caseId}/fix-plan")
    public FixPlanResponse plan(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean useCompanyLlm,
            @RequestParam(defaultValue = "5") int maxCandidates
    ) {
        try {
            return fixPlanService.plan(
                    caseId,
                    useCompanyLlm,
                    Math.max(1, maxCandidates)
            );
        } catch (Exception exception) {
            log.warn(
                    "Fix plan endpoint fallback caseId={} exceptionClass={} exceptionMessage={}",
                    caseId,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception
            );
            return new FixPlanResponse(
                    caseId,
                    "",
                    "HYPOTHESIS",
                    0.0,
                    List.of(),
                    null,
                    List.of(),
                    List.of(),
                    false,
                    true,
                    List.of(FixPlanService.FIX_PLAN_SOURCE_ANALYSIS_FAILED)
            );
        }
    }
}
