package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotJiraPreviewResponse;
import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotResponse;
import com.etiya.replayfix.service.GoldenPathEvidenceSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class GoldenPathEvidenceSnapshotController {

    private final GoldenPathEvidenceSnapshotService service;

    public GoldenPathEvidenceSnapshotController(
            GoldenPathEvidenceSnapshotService service
    ) {
        this.service = service;
    }

    @GetMapping("/{caseId}/golden-path/evidence-snapshot")
    public GoldenPathEvidenceSnapshotResponse snapshot(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean includeRawEvidence,
            @RequestParam(defaultValue = "true") boolean includeRovoBlock,
            @RequestParam(defaultValue = "true") boolean includeJiraMarkdown,
            @RequestParam(defaultValue = "true") boolean includeDeterministicRca
    ) {
        return service.snapshot(
                caseId,
                includeRawEvidence,
                includeRovoBlock,
                includeJiraMarkdown,
                includeDeterministicRca
        );
    }

    @GetMapping("/{caseId}/golden-path/evidence-snapshot/jira-preview")
    public GoldenPathEvidenceSnapshotJiraPreviewResponse jiraPreview(
            @PathVariable UUID caseId
    ) {
        return service.jiraPreview(caseId);
    }
}
