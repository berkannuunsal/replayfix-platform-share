package com.etiya.replayfix.api;

import com.etiya.replayfix.model.JiraWebhookAcceptanceResult;
import com.etiya.replayfix.model.JiraWebhookEvent;
import com.etiya.replayfix.service.AuditService;
import com.etiya.replayfix.service.JiraWebhookIdempotencyChecker;
import com.etiya.replayfix.service.JiraWebhookSecurityValidator;
import com.etiya.replayfix.service.ReplayFixWorkflowOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
public class JiraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookController.class);

    private final JiraWebhookSecurityValidator securityValidator;
    private final JiraWebhookIdempotencyChecker idempotencyChecker;
    private final ReplayFixWorkflowOrchestrator orchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public JiraWebhookController(
            JiraWebhookSecurityValidator securityValidator,
            JiraWebhookIdempotencyChecker idempotencyChecker,
            ReplayFixWorkflowOrchestrator orchestrator,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.securityValidator = securityValidator;
        this.idempotencyChecker = idempotencyChecker;
        this.orchestrator = orchestrator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/jira")
    public ResponseEntity<JiraWebhookAcceptanceResult> acceptJiraWebhook(
            @RequestHeader(value = "X-ReplayFix-Webhook-Secret", required = false) String secret,
            @RequestHeader(value = "X-Atlassian-Webhook-Identifier", required = false) String deliveryId,
            @RequestBody String body
    ) {
        try {
            JiraWebhookEvent event = parseWebhookEvent(body, deliveryId);

            var validation = securityValidator.validate(
                    secret,
                    event.webhookEvent(),
                    event.projectKey(),
                    event.issueType(),
                    event.eventTimestamp(),
                    body
            );

            if (!validation.valid()) {
                log.warn("Webhook validation failed: {}", validation.errorMessage());
                return ResponseEntity
                        .status(HttpStatus.FORBIDDEN)
                        .body(new JiraWebhookAcceptanceResult(
                                false,
                                false,
                                event.deliveryId(),
                                event.issueKey(),
                                null,
                                null,
                                null,
                                List.of(validation.errorMessage())
                        ));
            }

            var idempotency = idempotencyChecker.check(event);

            if (idempotency.duplicate()) {
                log.info("Duplicate webhook delivery: {}", event.deliveryId());

                auditService.record(
                        idempotency.existingCaseId(),
                        "JIRA_WEBHOOK_DUPLICATE_IGNORED",
                        "system",
                        "Duplicate webhook for " + event.issueKey()
                );

                return ResponseEntity.ok(new JiraWebhookAcceptanceResult(
                        true,
                        true,
                        idempotency.existingDeliveryId(),
                        event.issueKey(),
                        idempotency.existingCaseId(),
                        idempotency.existingWorkflowRunId(),
                        "DUPLICATE",
                        List.of()
                ));
            }

            var workflowRunId = orchestrator.startFromJiraWebhook(event);

            var workflowRun = orchestrator.getRun(workflowRunId);

            idempotencyChecker.record(event, workflowRun.caseId(), workflowRunId);

            auditService.record(
                    workflowRun.caseId(),
                    "JIRA_WEBHOOK_ACCEPTED",
                    "system",
                    "Webhook accepted for " + event.issueKey()
            );

            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(new JiraWebhookAcceptanceResult(
                            true,
                            false,
                            event.deliveryId(),
                            event.issueKey(),
                            workflowRun.caseId(),
                            workflowRunId,
                            workflowRun.status(),
                            List.of()
                    ));

        } catch (Exception e) {
            log.error("Failed to process Jira webhook", e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new JiraWebhookAcceptanceResult(
                            false,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            List.of("Internal error processing webhook")
                    ));
        }
    }

    private JiraWebhookEvent parseWebhookEvent(String body, String deliveryId) {
        try {
            JsonNode root = objectMapper.readTree(body);

            String webhookEvent = root.path("webhookEvent").asText("");
            String timestamp = root.path("timestamp").asText(null);

            JsonNode issue = root.path("issue");
            String issueKey = issue.path("key").asText("");
            String issueId = issue.path("id").asText("");

            JsonNode fields = issue.path("fields");
            String summary = fields.path("summary").asText("");
            String status = fields.path("status").path("name").asText("");

            JsonNode issueType = fields.path("issuetype");
            String issueTypeName = issueType.path("name").asText("");

            JsonNode project = fields.path("project");
            String projectKey = project.path("key").asText("");

            JsonNode user = root.path("user");
            String actorAccountId = user.path("accountId").asText("");

            JsonNode changelog = root.path("changelog");
            Map<String, Object> changedFields = new HashMap<>();

            if (changelog.has("items")) {
                for (JsonNode item : changelog.path("items")) {
                    String field = item.path("field").asText("");
                    changedFields.put(field, item.toString());
                }
            }

            String bodySha256 = securityValidator.computeBodyHash(body);

            return new JiraWebhookEvent(
                    webhookEvent,
                    issueKey,
                    issueId,
                    projectKey,
                    issueTypeName,
                    status,
                    summary,
                    actorAccountId,
                    timestamp,
                    bodySha256,
                    deliveryId,
                    changedFields
            );

        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse webhook body", e);
        }
    }
}
