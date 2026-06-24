package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.JiraFinalUpdatePreviewResponse;
import com.etiya.replayfix.service.JiraFinalUpdatePreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class JiraFinalUpdatePreviewController {

    private final JiraFinalUpdatePreviewService service;

    public JiraFinalUpdatePreviewController(
            JiraFinalUpdatePreviewService service
    ) {
        this.service = service;
    }

    @GetMapping("/{caseId}/jira-final-update-preview")
    public JiraFinalUpdatePreviewResponse preview(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean includeRca,
            @RequestParam(defaultValue = "true") boolean includeTestPlan,
            @RequestParam(defaultValue = "true") boolean includePatchPlan,
            @RequestParam(defaultValue = "true") boolean includeReplayStatus
    ) {
        return service.preview(
                caseId,
                includeRca,
                includeTestPlan,
                includePatchPlan,
                includeReplayStatus
        );
    }
}
