package com.etiya.replayfix.api;

import com.etiya.replayfix.model.PatternInformedTestSourceResult;
import com.etiya.replayfix.service.PatternInformedTestSourceService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class PatternInformedTestSourceController {

    private final PatternInformedTestSourceService service;

    public PatternInformedTestSourceController(
            PatternInformedTestSourceService service
    ) {
        this.service = service;
    }

    @PostMapping("/{caseId}/generate-pattern-informed-test-source")
    public Mono<PatternInformedTestSourceResult> generate(
            @PathVariable UUID caseId
    ) {
        return Mono.fromCallable(() ->
                service.generate(caseId)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
