package com.etiya.replaylab.api;

import com.etiya.replaylab.model.WorkflowRunView;
import com.etiya.replaylab.service.ReplayLabWorkflowOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final ReplayLabWorkflowOrchestrator orchestrator;

    public WorkflowController(ReplayLabWorkflowOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/{runId}")
    public ResponseEntity<WorkflowRunView> getWorkflowRun(@PathVariable UUID runId) {
        try {
            WorkflowRunView view = orchestrator.getRun(runId);
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<List<WorkflowRunView>> getCaseWorkflows(@PathVariable UUID caseId) {
        List<WorkflowRunView> workflows = orchestrator.getCaseWorkflows(caseId);
        return ResponseEntity.ok(workflows);
    }
}
