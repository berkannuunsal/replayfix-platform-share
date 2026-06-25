package com.etiya.replaylab.api;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.RepositoryResolutionResult;
import com.etiya.replaylab.service.EvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class RepositoryResolutionController {

    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public RepositoryResolutionController(
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/repository-resolution")
    public Mono<RepositoryResolutionResult> resolution(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() -> {

            EvidenceEntity evidence =
                    evidenceService.list(id)
                            .stream()
                            .filter(item ->
                                    item.getEvidenceType()
                                            == EvidenceType
                                            .REPOSITORY_RESOLUTION
                            )
                            .reduce((first, second) -> second)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Repository resolution "
                                                    + "not found for case: "
                                                    + id
                                    )
                            );

            return objectMapper.readValue(
                    evidence.getContentText(),
                    RepositoryResolutionResult.class
            );

        }).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
