package com.etiya.replaylab.api;

import com.etiya.replaylab.model.StructuredAiRootCauseAnalysis;
import com.etiya.replaylab.service.AiIncidentAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class AiIncidentAnalysisController {

    private final AiIncidentAnalysisService analysisService;

    public AiIncidentAnalysisController(AiIncidentAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/{caseId}/analyze-ai")
    public ResponseEntity<?> analyzeAi(@PathVariable UUID caseId) {
        try {
            StructuredAiRootCauseAnalysis analysis = analysisService.analyze(caseId);
            return ResponseEntity.ok(analysis);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(
                    java.util.Map.of("error", "NOT_FOUND", "message", e.getMessage())
            );
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("INSUFFICIENT_EVIDENCE")) {
                return ResponseEntity.status(422).body(
                        java.util.Map.of("error", "INSUFFICIENT_EVIDENCE", "message", e.getMessage())
                );
            } else if (e.getMessage().contains("disabled")) {
                return ResponseEntity.status(409).body(
                        java.util.Map.of("error", "AI_DISABLED", "message", e.getMessage())
                );
            }
            return ResponseEntity.status(400).body(
                    java.util.Map.of("error", "BAD_REQUEST", "message", e.getMessage())
            );
        } catch (Exception e) {
            return ResponseEntity.status(500).body(
                    java.util.Map.of("error", "INTERNAL_ERROR", "message", "AI analysis failed")
            );
        }
    }
}
