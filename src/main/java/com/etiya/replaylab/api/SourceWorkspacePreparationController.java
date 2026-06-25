package com.etiya.replaylab.api;

import com.etiya.replaylab.model.SourceWorkspacePreparationResponse;
import com.etiya.replaylab.service.SourceWorkspacePreparationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class SourceWorkspacePreparationController {

    private final SourceWorkspacePreparationService service;

    public SourceWorkspacePreparationController(
            SourceWorkspacePreparationService service
    ) {
        this.service = service;
    }

    @PostMapping("/{caseId}/source/prepare-workspace")
    public Mono<SourceWorkspacePreparationResponse> prepareWorkspace(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return Mono.fromCallable(() -> service.prepare(caseId, force))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
