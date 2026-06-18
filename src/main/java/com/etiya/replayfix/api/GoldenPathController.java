package com.etiya.replayfix.api;

import com.etiya.replayfix.service.GoldenPathOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Golden Path Controller for demo purposes.
 * Provides end-to-end orchestration endpoint for real incident evidence collection.
 * NO WRITE OPERATIONS to external systems (Jira, Git, Jenkins, Kubernetes, etc.)
 */
@RestController
@RequestMapping("/api/v1/golden-path")
public class GoldenPathController {

    private final GoldenPathOrchestrationService orchestrationService;

    public GoldenPathController(GoldenPathOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    /**
     * Execute golden path for a given Jira issue.
     * Example: POST /api/v1/golden-path/execute?jiraKey=FIZZMS-8346&targetKey=fizz-marketplace-service&forceNew=false
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestParam String jiraKey,
            @RequestParam String targetKey,
            @RequestParam(defaultValue = "false") boolean forceNew
    ) {
        Map<String, Object> result = orchestrationService.executeGoldenPath(jiraKey, targetKey, forceNew);
        return ResponseEntity.ok(result);
    }
}
