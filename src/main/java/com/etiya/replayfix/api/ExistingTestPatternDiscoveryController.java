package com.etiya.replayfix.api;

import com.etiya.replayfix.model.TestPatternDiscoveryResult;
import com.etiya.replayfix.service.ExistingTestPatternDiscoveryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class ExistingTestPatternDiscoveryController {

    private final ExistingTestPatternDiscoveryService discoveryService;

    public ExistingTestPatternDiscoveryController(
            ExistingTestPatternDiscoveryService discoveryService
    ) {
        this.discoveryService = discoveryService;
    }

    @PostMapping("/{caseId}/discover-test-pattern")
    public Mono<TestPatternDiscoveryResult> discover(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() ->
                discoveryService.discover(caseId)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
