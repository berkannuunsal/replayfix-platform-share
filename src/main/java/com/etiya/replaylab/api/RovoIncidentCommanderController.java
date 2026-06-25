package com.etiya.replaylab.api;

import com.etiya.replaylab.model.RovoAnalysisSubmission;
import com.etiya.replaylab.model.RovoIncidentContext;
import com.etiya.replaylab.service.RovoIncidentCommanderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Rovo Incident Commander Integration Controller.
 * Provides sanitized context to Rovo and receives RCA analysis from Rovo.
 * 
 * Contract for Rovo Forge Action / Forge Remote:
 * 1. GET /api/v1/rovo/incidents/{jiraKey}/context - Get sanitized incident context
 * 2. POST /api/v1/rovo/incidents/{jiraKey}/analysis - Submit Rovo RCA analysis
 * 
 * NO POLLING from ReplayLab - Rovo will push analysis via POST endpoint.
 */
@RestController
@RequestMapping("/api/v1/rovo/incidents")
public class RovoIncidentCommanderController {

    private final RovoIncidentCommanderService rovoService;

    public RovoIncidentCommanderController(RovoIncidentCommanderService rovoService) {
        this.rovoService = rovoService;
    }

    /**
     * GET sanitized incident context for Rovo Incident Commander.
     * Returns all relevant evidence in a sanitized format for Rovo analysis.
     * 
     * Example: GET /api/v1/rovo/incidents/FIZZMS-8346/context
     */
    @GetMapping("/{jiraKey}/context")
    public ResponseEntity<RovoIncidentContext> getIncidentContext(@PathVariable String jiraKey) {
        try {
            RovoIncidentContext context = rovoService.getIncidentContext(jiraKey);
            return ResponseEntity.ok(context);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST Rovo RCA analysis submission.
     * Rovo Forge Action will call this endpoint to submit structured RCA analysis.
     * Creates ROVO_RCA evidence with source="rovo-incident-commander".
     * 
     * Example: POST /api/v1/rovo/incidents/FIZZMS-8346/analysis
     */
    @PostMapping("/{jiraKey}/analysis")
    public ResponseEntity<Void> submitAnalysis(
            @PathVariable String jiraKey,
            @RequestBody RovoAnalysisSubmission analysis
    ) {
        try {
            rovoService.submitAnalysis(jiraKey, analysis);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
