package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.GeneratedDraftReviewResponse;
import com.etiya.replaylab.service.GeneratedDraftReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class GeneratedDraftReviewController {

    private final GeneratedDraftReviewService service;

    public GeneratedDraftReviewController(GeneratedDraftReviewService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/generated-draft-review")
    public GeneratedDraftReviewResponse review(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true")
            boolean includeFileContentPreview,
            @RequestParam(defaultValue = "1200") int maxPreviewChars
    ) {
        return service.review(
                caseId,
                includeFileContentPreview,
                maxPreviewChars
        );
    }
}
