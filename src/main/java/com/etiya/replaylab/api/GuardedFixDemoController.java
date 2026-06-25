package com.etiya.replaylab.api;

import com.etiya.replaylab.api.dto.GuardedFixDemoPreviewRequest;
import com.etiya.replaylab.api.dto.GuardedFixDemoPreviewResponse;
import com.etiya.replaylab.api.dto.GuardedFixDemoTestOnlyPrRequest;
import com.etiya.replaylab.api.dto.GuardedFixDemoTestOnlyPrResponse;
import com.etiya.replaylab.service.GuardedFixDemoService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class GuardedFixDemoController {

    private final GuardedFixDemoService service;

    public GuardedFixDemoController(GuardedFixDemoService service) {
        this.service = service;
    }

    @PostMapping("/cases/{caseId}/guarded-fix-demo/preview")
    public GuardedFixDemoPreviewResponse preview(
            @PathVariable UUID caseId,
            @RequestBody(required = false) GuardedFixDemoPreviewRequest request
    ) {
        return service.preview(caseId, request);
    }

    @PostMapping("/cases/{caseId}/guarded-fix-demo/test-only-pr/execute")
    public GuardedFixDemoTestOnlyPrResponse executeTestOnlyPr(
            @PathVariable UUID caseId,
            @RequestBody GuardedFixDemoTestOnlyPrRequest request
    ) {
        return service.executeTestOnlyPr(caseId, request);
    }
}
