package com.etiya.replayfix.api;

import com.etiya.replayfix.model.LocalRegressionTestExecutionRequest;
import com.etiya.replayfix.model.LocalRegressionTestExecutionResult;
import com.etiya.replayfix.service.ApprovedLocalRegressionTestExecutionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class ApprovedLocalRegressionTestExecutionController {

    private final ApprovedLocalRegressionTestExecutionService
            executionService;

    public ApprovedLocalRegressionTestExecutionController(
            ApprovedLocalRegressionTestExecutionService executionService
    ) {
        this.executionService =
                executionService;
    }

    @PostMapping("/{caseId}/execute-approved-regression-test")
    public Mono<LocalRegressionTestExecutionResult> execute(
            @PathVariable UUID caseId,
            @RequestBody LocalRegressionTestExecutionRequest request
    ) {
        return Mono.fromCallable(() ->
                executionService.execute(
                        caseId,
                        request.approvalId()
                )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
