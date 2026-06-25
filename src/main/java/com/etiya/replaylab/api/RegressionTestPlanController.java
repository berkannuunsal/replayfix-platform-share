package com.etiya.replaylab.api;

import com.etiya.replaylab.model.RegressionTestPlanResult;
import com.etiya.replaylab.service.RegressionTestPlanService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class RegressionTestPlanController {

    private final RegressionTestPlanService
            planService;

    public RegressionTestPlanController(
            RegressionTestPlanService planService
    ) {
        this.planService = planService;
    }

    @PostMapping("/{id}/generate-regression-test-plan")
    public Mono<RegressionTestPlanResult> generate(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                planService.generate(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
