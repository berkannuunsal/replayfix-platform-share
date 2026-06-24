package com.etiya.replayfix.api;

import com.etiya.replayfix.api.dto.TestExecutionApprovalRequest;
import com.etiya.replayfix.api.dto.TestExecutionGuardResponse;
import com.etiya.replayfix.service.TestExecutionGuardService;
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
public class TestExecutionGuardController {

    private final TestExecutionGuardService service;
    private final ObjectMapper objectMapper;

    public TestExecutionGuardController(
            TestExecutionGuardService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{caseId}/test-execution/preview")
    public TestExecutionGuardResponse preview(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        return service.preview(caseId, dryRun);
    }

    @PostMapping(
            value = "/{caseId}/test-execution/apply",
            consumes = MediaType.ALL_VALUE
    )
    public ResponseEntity<TestExecutionGuardResponse> apply(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestBody(required = false) String approvalBody
    ) {
        TestExecutionGuardResponse response = service.apply(
                caseId,
                parseApproval(approvalBody),
                dryRun
        );
        if ("BLOCKED_BY_MISSING_APPROVAL".equals(response.executionStatus())
                || "BLOCKED_BY_EXECUTION_NOT_ENABLED".equals(response.executionStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.ok(response);
    }

    private TestExecutionApprovalRequest parseApproval(String approvalBody) {
        if (approvalBody == null || approvalBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    approvalBody,
                    TestExecutionApprovalRequest.class
            );
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid test execution approval body"
            );
        }
    }
}
