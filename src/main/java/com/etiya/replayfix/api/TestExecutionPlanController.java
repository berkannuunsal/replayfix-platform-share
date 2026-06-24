package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import com.etiya.replayfix.service.TestExecutionPlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class TestExecutionPlanController {

    private final TestExecutionPlanService service;

    public TestExecutionPlanController(TestExecutionPlanService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/test-execution-plan")
    public TestExecutionPlanResponse plan(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean includeWorkspaceDrafts,
            @RequestParam(defaultValue = "true") boolean includeReplayReadiness,
            @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        return service.plan(
                caseId,
                includeWorkspaceDrafts,
                includeReplayReadiness,
                dryRun
        );
    }
}
