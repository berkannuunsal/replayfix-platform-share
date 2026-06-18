package com.etiya.replayfix.api;

import com.etiya.replayfix.model.ReplayFixEvidenceSnapshot;
import com.etiya.replayfix.model.RovoRcaImportResponse;
import com.etiya.replayfix.service.EvidenceSnapshotService;
import com.etiya.replayfix.service.RovoRcaImporterService;
import com.etiya.replayfix.service.RovoSnapshotPublisherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/rovo")
public class RovoIntegrationController {

    private final EvidenceSnapshotService snapshotService;
    private final RovoSnapshotPublisherService publisherService;
    private final RovoRcaImporterService importerService;

    public RovoIntegrationController(
            EvidenceSnapshotService snapshotService,
            RovoSnapshotPublisherService publisherService,
            RovoRcaImporterService importerService
    ) {
        this.snapshotService = snapshotService;
        this.publisherService = publisherService;
        this.importerService = importerService;
    }

    @GetMapping("/evidence-snapshot")
    public ResponseEntity<ReplayFixEvidenceSnapshot> getEvidenceSnapshot(
            @PathVariable UUID caseId
    ) {
        ReplayFixEvidenceSnapshot snapshot = snapshotService.buildSnapshot(caseId);
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/publish-snapshot")
    public ResponseEntity<Map<String, Object>> publishSnapshot(
            @PathVariable UUID caseId
    ) {
        Map<String, Object> result = publisherService.publishToJira(caseId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/import-rca")
    public ResponseEntity<RovoRcaImportResponse> importRovoRca(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        RovoRcaImportResponse response = importerService.importFromJira(caseId, force);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/import-rca/manual")
    public ResponseEntity<RovoRcaImportResponse> importRovoRcaManual(
            @PathVariable UUID caseId,
            @RequestBody Map<String, String> request
    ) {
        String rawComment = request.get("rawComment");
        if (rawComment == null || rawComment.isBlank()) {
            RovoRcaImportResponse.ImportDiagnostics emptyDiagnostics = 
                    new RovoRcaImportResponse.ImportDiagnostics(
                            0, 0, 0, 0, List.of(), null, null, 
                            List.of(), List.of(), null, null
                    );
            return ResponseEntity.badRequest().body(
                    RovoRcaImportResponse.error(caseId, null, "rawComment is required", emptyDiagnostics)
            );
        }

        boolean force = Boolean.parseBoolean(request.getOrDefault("force", "false"));
        RovoRcaImportResponse response = importerService.importManual(caseId, rawComment, force);
        return ResponseEntity.ok(response);
    }
}
