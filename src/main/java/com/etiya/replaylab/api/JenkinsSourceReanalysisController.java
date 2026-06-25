package com.etiya.replaylab.api;

import com.etiya.replaylab.model.JenkinsSourceReanalysisResult;
import com.etiya.replaylab.service.JenkinsSourceReanalysisService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class JenkinsSourceReanalysisController {

    private final JenkinsSourceReanalysisService
            reanalysisService;

    public JenkinsSourceReanalysisController(
            JenkinsSourceReanalysisService reanalysisService
    ) {
        this.reanalysisService = reanalysisService;
    }

    @PostMapping("/{id}/reanalyze-with-jenkins-commit")
    public Mono<JenkinsSourceReanalysisResult> reanalyze(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                reanalysisService.reanalyze(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
