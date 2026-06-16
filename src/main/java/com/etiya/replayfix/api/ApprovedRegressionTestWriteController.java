package com.etiya.replayfix.api;

import com.etiya.replayfix.model.GeneratedTestWriteRequest;
import com.etiya.replayfix.model.GeneratedTestWriteResult;
import com.etiya.replayfix.service.ApprovedRegressionTestWriteService;
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
public class ApprovedRegressionTestWriteController {

    private final ApprovedRegressionTestWriteService
            writeService;

    public ApprovedRegressionTestWriteController(
            ApprovedRegressionTestWriteService writeService
    ) {
        this.writeService =
                writeService;
    }

    @PostMapping("/{caseId}/write-approved-regression-test")
    public Mono<GeneratedTestWriteResult> write(
            @PathVariable UUID caseId,
            @RequestBody GeneratedTestWriteRequest request
    ) {
        return Mono.fromCallable(() ->
                writeService.write(
                        caseId,
                        request.approvalId()
                )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
