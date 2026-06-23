package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.WorkspaceWriteApprovalRequest;
import com.etiya.replayfix.api.dto.WorkspaceWriteResponse;
import com.etiya.replayfix.service.WorkspaceWriteService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class WorkspaceWriteController {

    private final WorkspaceWriteService service;
    private final ObjectMapper objectMapper;

    public WorkspaceWriteController(
            WorkspaceWriteService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{caseId}/workspace-write/preview")
    public WorkspaceWriteResponse preview(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "true") boolean includeTestDraft,
            @RequestParam(defaultValue = "true") boolean includeFixDraft
    ) {
        return service.preview(caseId, dryRun, includeTestDraft, includeFixDraft);
    }

    @PostMapping(
            value = "/{caseId}/workspace-write/apply",
            consumes = MediaType.ALL_VALUE
    )
    public ResponseEntity<WorkspaceWriteResponse> apply(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "true") boolean includeTestDraft,
            @RequestParam(defaultValue = "true") boolean includeFixDraft,
            @RequestBody(required = false) String approvalBody
    ) {
        WorkspaceWriteResponse response = service.apply(
                caseId,
                parseApproval(approvalBody),
                dryRun,
                includeTestDraft,
                includeFixDraft
        );
        if ("BLOCKED_BY_MISSING_APPROVAL".equals(response.writeStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.ok(response);
    }

    private WorkspaceWriteApprovalRequest parseApproval(String approvalBody) {
        if (approvalBody == null || approvalBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    approvalBody,
                    WorkspaceWriteApprovalRequest.class
            );
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid workspace write approval body"
            );
        }
    }
}
