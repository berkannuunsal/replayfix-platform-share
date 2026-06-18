package com.etiya.replayfix.api;

import com.etiya.replayfix.model.RegressionTestHypothesisResult;
import com.etiya.replayfix.service.RegressionTestHypothesisService;
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
public class RegressionTestHypothesisController {

    private final RegressionTestHypothesisService hypothesisService;

    public RegressionTestHypothesisController(
            RegressionTestHypothesisService hypothesisService
    ) {
        this.hypothesisService = hypothesisService;
    }

    @PostMapping("/{caseId}/generate-regression-test-hypothesis")
    public Mono<RegressionTestHypothesisResult> generate(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return Mono.fromCallable(() ->
                hypothesisService.generate(caseId, force)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
