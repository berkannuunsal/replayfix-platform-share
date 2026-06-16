package com.etiya.replayfix.api;

import com.etiya.replayfix.model.JenkinsCaseEvidence;
import com.etiya.replayfix.service.JenkinsEvidenceCollectorService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class JenkinsEvidenceController {

    private final JenkinsEvidenceCollectorService collectorService;

    public JenkinsEvidenceController(
            JenkinsEvidenceCollectorService collectorService
    ) {
        this.collectorService = collectorService;
    }

    @PostMapping("/{id}/collect-jenkins")
    public Mono<JenkinsCaseEvidence> collect(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                collectorService.collect(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
