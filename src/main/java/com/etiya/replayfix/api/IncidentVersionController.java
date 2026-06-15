package com.etiya.replayfix.api;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.IncidentVersionResolution;
import com.etiya.replayfix.service.EvidenceService;
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
public class IncidentVersionController {

    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public IncidentVersionController(
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/incident-version")
    public Mono<IncidentVersionResolution> version(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() -> {

            EvidenceEntity evidence =
                    evidenceService.list(id)
                            .stream()
                            .filter(item ->
                                    item.getEvidenceType()
                                            == EvidenceType
                                            .INCIDENT_VERSION
                            )
                            .reduce(
                                    (first, second) -> second
                            )
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Incident version not found "
                                                    + "for case: "
                                                    + id
                                    )
                            );

            return objectMapper.readValue(
                    evidence.getContentText(),
                    IncidentVersionResolution.class
            );

        }).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
