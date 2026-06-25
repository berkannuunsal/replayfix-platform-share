package com.etiya.replaylab.api;

import com.etiya.replaylab.model.FailingRegressionTestDraftResult;
import com.etiya.replaylab.service.FailingRegressionTestDraftService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class FailingRegressionTestDraftController {

    private final FailingRegressionTestDraftService draftService;

    public FailingRegressionTestDraftController(
            FailingRegressionTestDraftService draftService
    ) {
        this.draftService = draftService;
    }

    @PostMapping("/{caseId}/generate-failing-regression-test-draft")
    public Mono<FailingRegressionTestDraftResult> generate(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return Mono.fromCallable(() ->
                draftService.generate(caseId, force)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
