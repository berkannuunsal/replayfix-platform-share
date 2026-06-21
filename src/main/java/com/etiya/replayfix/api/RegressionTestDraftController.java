package com.etiya.replayfix.api;

import com.etiya.replayfix.model.RegressionTestDraftResponse;
import com.etiya.replayfix.service.RegressionTestDraftService;
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
public class RegressionTestDraftController {

    private static final Logger log = LoggerFactory.getLogger(
            RegressionTestDraftController.class
    );

    private final RegressionTestDraftService service;

    public RegressionTestDraftController(RegressionTestDraftService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/regression-test-draft")
    public RegressionTestDraftResponse draft(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean useCompanyLlm,
            @RequestParam(defaultValue = "3") int maxScenarios
    ) {
        try {
            return service.draft(
                    caseId,
                    useCompanyLlm,
                    Math.max(1, maxScenarios)
            );
        } catch (Exception exception) {
            log.warn(
                    "Regression test draft endpoint fallback caseId={} exceptionClass={} exceptionMessage={}",
                    caseId,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception
            );
            return new RegressionTestDraftResponse(
                    caseId,
                    "",
                    "HYPOTHESIS",
                    List.of("UNKNOWN"),
                    "UNKNOWN",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    true,
                    List.of(RegressionTestDraftService
                            .REGRESSION_DRAFT_SOURCE_ANALYSIS_FAILED)
            );
        }
    }
}
