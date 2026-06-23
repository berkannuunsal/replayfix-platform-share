package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.CreateReplayInputRequest;
import com.etiya.replayfix.api.dto.ReplayInputResponse;
import com.etiya.replayfix.service.ReplayInputService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/replay-inputs")
public class ReplayInputController {

    private final ReplayInputService replayInputService;

    public ReplayInputController(ReplayInputService replayInputService) {
        this.replayInputService = replayInputService;
    }

    @PostMapping
    public ResponseEntity<ReplayInputResponse> create(
            @PathVariable UUID caseId,
            @RequestBody CreateReplayInputRequest request
    ) {
        ReplayInputResponse response = replayInputService.create(
                caseId,
                request
        );
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/cases/"
                                + caseId
                                + "/replay-inputs/"
                                + response.id()
                ))
                .body(response);
    }

    @GetMapping
    public List<ReplayInputResponse> list(@PathVariable UUID caseId) {
        return replayInputService.list(caseId);
    }

    @GetMapping("/latest")
    public ResponseEntity<ReplayInputResponse> latest(
            @PathVariable UUID caseId
    ) {
        return replayInputService.latest(caseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .build());
    }
}
