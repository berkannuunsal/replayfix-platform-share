package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replayfix.api.dto.GoldenPathEvidenceSnapshotResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskRequest;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.TestExecutionPlanResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.JiraClient;
import com.etiya.replayfix.model.IntegrationModels.JiraIssueCreateResult;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JiraRealActionService {

    private static final String SOURCE = "replayfix-real-action-jira-test-task";

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final JiraClient jiraClient;
    private final TestExecutionPlanService testExecutionPlanService;
    private final CodeChangeAdvisoryService advisoryService;
    private final GoldenPathEvidenceSnapshotService snapshotService;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public JiraRealActionService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            JiraClient jiraClient,
            TestExecutionPlanService testExecutionPlanService,
            CodeChangeAdvisoryService advisoryService,
            GoldenPathEvidenceSnapshotService snapshotService,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.jiraClient = jiraClient;
        this.testExecutionPlanService = testExecutionPlanService;
        this.advisoryService = advisoryService;
        this.snapshotService = snapshotService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public JiraTestTaskResponse preview(UUID caseId, JiraTestTaskRequest request) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        return build(replayCase, safeRequest(request), true, false);
    }

    @Transactional
    public JiraTestTaskResponse create(UUID caseId, JiraTestTaskRequest request) {
        ReplayCaseEntity replayCase = caseEntity(caseId);
        JiraTestTaskRequest safeRequest = safeRequest(request);
        validateCreate(safeRequest);
        JiraTestTaskResponse preview = build(replayCase, safeRequest, true, false);
        JiraIssueCreateResult created = jiraClient.createIssue(
                preview.jiraPayloadPreview()
        );
        if (!created.success()) {
            return new JiraTestTaskResponse(
                    replayCase.getId(),
                    replayCase.getJiraKey(),
                    false,
                    false,
                    "",
                    "",
                    preview.parentJiraKey(),
                    preview.issueType(),
                    preview.summary(),
                    preview.descriptionPreview(),
                    preview.jiraPayloadPreview(),
                    List.of("JIRA_CREATE_FAILED"),
                    unique(join(preview.warnings(), created.warnings())),
                    List.of("Review Jira create failure and retry after approval."),
                    Instant.now()
            );
        }
        JiraTestTaskResponse response = new JiraTestTaskResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                false,
                true,
                nullToBlank(created.issueKey()),
                nullToBlank(created.issueUrl()),
                preview.parentJiraKey(),
                preview.issueType(),
                preview.summary(),
                preview.descriptionPreview(),
                preview.jiraPayloadPreview(),
                List.of(),
                preview.warnings(),
                List.of("Review created Jira test task before executing tests."),
                Instant.now()
        );
        saveEvidence(replayCase.getId(), response);
        return response;
    }

    private JiraTestTaskResponse build(
            ReplayCaseEntity replayCase,
            JiraTestTaskRequest request,
            boolean previewOnly,
            boolean created
    ) {
        List<String> warnings = new ArrayList<>();
        String sourceJira = firstNonBlank(
                request.parentJiraKey(),
                replayCase.getJiraKey()
        );
        String issueType = request.createAsSubTask()
                ? firstNonBlank(properties.getRealActions().getJiraSubTaskIssueType(), request.issueType())
                : firstNonBlank(request.issueType(), "Task");
        String summary = firstNonBlank(
                request.summaryPrefix(),
                "ReplayLab Test Execution"
        ) + " - " + sourceJira;
        if (!summary.contains("ReplayLab")) {
            summary = "ReplayLab " + summary;
        }

        TestExecutionPlanResponse testPlan = null;
        if (request.includeTestExecutionPlan()) {
            try {
                testPlan = testExecutionPlanService.plan(
                        replayCase.getId(),
                        true,
                        true,
                        true
                );
            } catch (Exception exception) {
                warnings.add("TEST_EXECUTION_PLAN_UNAVAILABLE");
            }
        }
        CodeChangeAdvisoryEvaluationSummaryResponse advisory = null;
        if (request.includeCodeAdvisory()) {
            try {
                advisory = advisoryService.summary(replayCase.getId());
            } catch (Exception exception) {
                warnings.add("CODE_ADVISORY_SUMMARY_UNAVAILABLE");
            }
        }
        GoldenPathEvidenceSnapshotResponse snapshot = null;
        if (request.includeEvidenceSnapshot()) {
            try {
                snapshot = snapshotService.snapshot(
                        replayCase.getId(),
                        false,
                        false,
                        false,
                        true
                );
            } catch (Exception exception) {
                warnings.add("EVIDENCE_SNAPSHOT_UNAVAILABLE");
            }
        }

        String description = description(
                replayCase,
                sourceJira,
                snapshot,
                advisory,
                testPlan
        );
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", projectKey(sourceJira)));
        fields.put("issuetype", Map.of("name", issueType));
        fields.put("summary", summary);
        fields.put("description", adf(description));
        if (request.createAsSubTask()) {
            fields.put("parent", Map.of("key", sourceJira));
        }
        Map<String, Object> payload = Map.of("fields", fields);

        return new JiraTestTaskResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                previewOnly,
                created,
                "",
                "",
                sourceJira,
                issueType,
                summary,
                description,
                payload,
                List.of(),
                unique(warnings),
                List.of(
                        "Human review required before creating or executing the task.",
                        "Do not include raw logs, secrets, or production payloads."
                ),
                Instant.now()
        );
    }

    private String description(
            ReplayCaseEntity replayCase,
            String sourceJira,
            GoldenPathEvidenceSnapshotResponse snapshot,
            CodeChangeAdvisoryEvaluationSummaryResponse advisory,
            TestExecutionPlanResponse testPlan
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("ReplayLab Generated Test Task");
        lines.add("");
        lines.add("Source Jira:");
        lines.add("- " + sourceJira);
        lines.add("");
        lines.add("ReplayLab Case:");
        lines.add("- " + replayCase.getId());
        lines.add("");
        lines.add("Evidence:");
        if (snapshot == null) {
            lines.add("- Evidence snapshot unavailable");
        } else {
            lines.add("- repository: " + snapshot.repository().projectKey()
                    + "/" + snapshot.repository().repositorySlug());
            lines.add("- branch: " + snapshot.repository().branch());
            lines.add("- commit: " + snapshot.repository().commitSha());
            lines.add("- Jenkins build: " + snapshot.jenkins().buildNumber());
            lines.add("- incident version status: "
                    + snapshot.incidentVersion().status());
            lines.add("- deterministic RCA status: "
                    + snapshot.deterministicRca().status());
        }
        lines.add("");
        lines.add("Planned Test Steps:");
        if (testPlan == null || testPlan.testCommands().isEmpty()) {
            lines.add("1. Review ReplayLab test execution plan.");
            lines.add("2. Prepare sanitized replay data.");
            lines.add("3. Execute approved tests manually.");
        } else {
            int index = 1;
            for (String command : testPlan.testCommands()) {
                lines.add(index++ + ". " + command);
            }
        }
        lines.add("");
        lines.add("Expected Result:");
        lines.add("- Generated regression test reproduces or guards the hypothesized defect after human approval.");
        lines.add("");
        if (advisory != null) {
            lines.add("Code Advisory:");
            lines.add("- generated count: " + advisory.advisoryGeneratedCount());
            lines.add("- case advisory status: " + advisory.caseAdvisoryStatus());
            lines.add("");
        }
        lines.add("Guardrails:");
        lines.add("- Advisory-only");
        lines.add("- Human review required");
        lines.add("- No production write");
        return sanitize(String.join("\n", lines));
    }

    private void validateCreate(JiraTestTaskRequest request) {
        List<String> errors = new ArrayList<>();
        if (isBlank(request.requestedBy())) {
            errors.add("REQUESTED_BY_REQUIRED");
        }
        if (properties.getRealActions().isRequireConfirmation()
                && !request.confirmCreate()) {
            errors.add("CONFIRM_CREATE_REQUIRED");
        }
        if (properties.getRealActions().isRequireGuardrailsAccepted()
                && !request.guardrailsAccepted()) {
            errors.add("GUARDRAILS_ACCEPTED_REQUIRED");
        }
        if (request.createAsSubTask()
                && isBlank(properties.getRealActions().getJiraSubTaskIssueType())) {
            errors.add("JIRA_SUBTASK_TYPE_NOT_CONFIGURED");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.join(",", errors)
            );
        }
        if (!properties.getRealActions().isEnabled()
                || !properties.getRealActions().isJiraCreateEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "REAL_ACTIONS_DISABLED"
            );
        }
    }

    private void saveEvidence(UUID caseId, JiraTestTaskResponse response) {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(EvidenceType.JIRA_TEST_TASK);
        entity.setSource(SOURCE);
        entity.setSanitized(true);
        entity.setContentText(toJson(response));
        evidenceRepository.save(entity);
    }

    private ReplayCaseEntity caseEntity(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
    }

    private Map<String, Object> adf(String text) {
        return Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of(
                                "type", "text",
                                "text", text
                        ))
                ))
        );
    }

    private String projectKey(String jiraKey) {
        if (isBlank(jiraKey) || !jiraKey.contains("-")) {
            return "FIZZMS";
        }
        return jiraKey.substring(0, jiraKey.indexOf('-'));
    }

    private List<String> join(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return values;
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null ? List.of() : values.stream()
                        .filter(value -> !isBlank(value))
                        .toList()
        ));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization", "[redacted]")
                .replaceAll("(?i)cookie", "[redacted]")
                .replaceAll("(?i)password", "[redacted]")
                .replaceAll("(?i)token", "[redacted]")
                .replaceAll("(?i)secret", "[redacted]")
                .replaceAll("(?i)raw payload", "[redacted]");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private JiraTestTaskRequest safeRequest(JiraTestTaskRequest request) {
        return request == null
                ? new JiraTestTaskRequest(
                        "",
                        "",
                        "Task",
                        false,
                        "ReplayLab Test Execution",
                        true,
                        true,
                        true,
                        false,
                        false
                )
                : request;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
