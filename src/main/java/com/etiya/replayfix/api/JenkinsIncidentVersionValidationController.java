package com.etiya.replayfix.api;

import com.etiya.replayfix.model.JenkinsIncidentVersionValidation;
import com.etiya.replayfix.service.JenkinsIncidentVersionValidationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class JenkinsIncidentVersionValidationController {

    private final JenkinsIncidentVersionValidationService
            validationService;

    public JenkinsIncidentVersionValidationController(
            JenkinsIncidentVersionValidationService validationService
    ) {
        this.validationService = validationService;
    }

    @PostMapping("/{id}/validate-jenkins-version")
    public Mono<JenkinsIncidentVersionValidation> validate(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                validationService.validate(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
