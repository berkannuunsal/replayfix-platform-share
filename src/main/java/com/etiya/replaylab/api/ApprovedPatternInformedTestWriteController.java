package com.etiya.replaylab.api;

import com.etiya.replaylab.model.PatternInformedTestWriteRequest;
import com.etiya.replaylab.model.PatternInformedTestWriteResult;
import com.etiya.replaylab.service.ApprovedPatternInformedTestWriteService;
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
public class ApprovedPatternInformedTestWriteController {

    private final ApprovedPatternInformedTestWriteService service;

    public ApprovedPatternInformedTestWriteController(
            ApprovedPatternInformedTestWriteService service
    ) {
        this.service = service;
    }

    @PostMapping(
            "/{caseId}/write-approved-pattern-informed-test"
    )
    public Mono<PatternInformedTestWriteResult> write(
            @PathVariable UUID caseId,
            @RequestBody PatternInformedTestWriteRequest request
    ) {
        return Mono.fromCallable(() ->
                service.write(
                        caseId,
                        request.approvalId()
                )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
