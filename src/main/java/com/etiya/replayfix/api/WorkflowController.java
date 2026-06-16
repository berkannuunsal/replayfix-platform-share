package com.etiya.replayfix.api;

import com.etiya.replayfix.model.WorkflowRunView;
import com.etiya.replayfix.service.ReplayFixWorkflowOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final ReplayFixWorkflowOrchestrator orchestrator;

    public WorkflowController(ReplayFixWorkflowOrchestrator orchestrator) {
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
