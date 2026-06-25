package com.etiya.replaylab.api;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.IncidentTimeline;
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
public class IncidentTimelineController {

    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public IncidentTimelineController(
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/timeline")
    public Mono<IncidentTimeline> timeline(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() -> {

            EvidenceEntity evidence =
                    evidenceService.list(id)
                            .stream()
                            .filter(item ->
                                    item.getEvidenceType()
                                            == EvidenceType.INCIDENT_TIMELINE
                            )
                            .reduce((first, second) -> second)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Incident timeline not found "
                                                    + "for case: "
                                                    + id
                                    )
                            );

            return objectMapper.readValue(
                    evidence.getContentText(),
                    IncidentTimeline.class
            );

        }).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
