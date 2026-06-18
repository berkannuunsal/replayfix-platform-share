package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.*;
import com.etiya.replayfix.model.*;
import com.etiya.replayfix.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IncidentDashboardService {

    private static final Logger log = LoggerFactory.getLogger(IncidentDashboardService.class);

    private final ReplayCaseRepository caseRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final EvidenceRepository evidenceRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final AuditEventRepository auditRepository;
    private final ReplayFixWorkflowOrchestrator orchestrator;
    private final JiraEvidenceCommentPreviewService previewService;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;
    private final EvidenceSanitizer evidenceSanitizer;

    public IncidentDashboardService(
            ReplayCaseRepository caseRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowStepRepository workflowStepRepository,
            EvidenceRepository evidenceRepository,
            ApprovalRequestRepository approvalRepository,
            AuditEventRepository auditRepository,
            ReplayFixWorkflowOrchestrator orchestrator,
            JiraEvidenceCommentPreviewService previewService,
            ReplayFixProperties properties,
            ObjectMapper objectMapper,
            EvidenceSanitizer evidenceSanitizer
    ) {
        this.caseRepository = caseRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.evidenceRepository = evidenceRepository;
        this.approvalRepository = approvalRepository;
        this.auditRepository = auditRepository;
        this.orchestrator = orchestrator;
        this.previewService = previewService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.evidenceSanitizer = evidenceSanitizer;
    }

    @Transactional(readOnly = true)
    public IncidentDashboardView getCaseDashboard(UUID caseId) {
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        CaseSummaryView caseSummary = buildCaseSummary(caseEntity);

        WorkflowRunView workflow = getLatestWorkflow(caseId);

        List<DashboardEvidenceCard> evidenceCards = buildEvidenceCards(caseId);

        RootCauseDashboardView rootCause = buildRootCauseView(caseId);

        RovoRcaDashboardView rovoRca = buildRovoRcaView(caseId);

        RegressionTestHypothesisDashboardView regressionTestHypothesis =
                buildRegressionTestHypothesisView(caseId);

        FailingRegressionTestDraftDashboardView failingRegressionTestDraft =
                buildFailingRegressionTestDraftView(caseId);

        List<MissingEvidenceView> missingEvidence = buildMissingEvidence(caseId, workflow);

        JiraEvidenceCommentPreview jiraPreview = getLatestPreview(caseId);

        List<ApprovalRequestView> approvals = getApprovals(caseId);

        List<AuditEventView> auditEvents = getRecentAuditEvents(caseId, 100);

        DashboardPolicyView policies = buildPolicyView();

        return new IncidentDashboardView(
                caseSummary,
                workflow,
                evidenceCards,
                rootCause,
                rovoRca,
                regressionTestHypothesis,
                failingRegressionTestDraft,
                missingEvidence,
                jiraPreview,
                approvals,
                auditEvents,
                policies,
                Instant.now().toString()
        );
    }

    @Transactional(readOnly = true)
    public List<CaseListItemView> listCases(String query, String status, int limit) {
        if (limit > 100) limit = 100;
        if (limit < 1) limit = 20;

        List<ReplayCaseEntity> cases = caseRepository.findAll()
                .stream()
                .limit(limit)
                .toList();

        return cases.stream()
                .map(this::toCaseListItem)
                .toList();
    }

    private CaseSummaryView buildCaseSummary(ReplayCaseEntity caseEntity) {
        return new CaseSummaryView(
                caseEntity.getId(),
                caseEntity.getJiraKey(),
                "Incident: " + caseEntity.getJiraKey(),
                caseEntity.getStatus() != null ? caseEntity.getStatus().name() : "UNKNOWN",
                "High",
                caseEntity.getTargetKey(),
                "Service degradation",
                "See evidence matrix",
                caseEntity.getCreatedAt(),
                caseEntity.getUpdatedAt()
        );
    }

    private WorkflowRunView getLatestWorkflow(UUID caseId) {
        Optional<WorkflowRunEntity> latestRun = workflowRunRepository
                .findFirstByCaseIdOrderByCreatedAtDesc(caseId);

        if (latestRun.isEmpty()) {
            return null;
        }

        return orchestrator.getRun(latestRun.get().getId());
    }

    private List<DashboardEvidenceCard> buildEvidenceCards(UUID caseId) {
        List<EvidenceEntity> allEvidence = evidenceRepository
                .findByCaseIdOrderByCreatedAtAsc(caseId);

        Map<String, List<EvidenceEntity>> groupedBySource = allEvidence.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getSource() != null ? e.getSource() : "UNKNOWN"
                ));

        List<DashboardEvidenceCard> cards = new ArrayList<>();

        for (String[] sourceInfo : getEvidenceSources()) {
            String source = sourceInfo[0];
            String displayName = sourceInfo[1];

            List<EvidenceEntity> sourceEvidence = groupedBySource.getOrDefault(source, List.of());

            String status = sourceEvidence.isEmpty() ? "UNAVAILABLE" : "CONFIRMED";
            String keyFinding = sourceEvidence.isEmpty() 
                    ? "No evidence collected" 
                    : "Evidence available";
            String confidence = sourceEvidence.isEmpty() ? "N/A" : "High";
            String lastCollected = sourceEvidence.isEmpty() 
                    ? null 
                    : sourceEvidence.get(sourceEvidence.size() - 1).getCreatedAt().toString();

            cards.add(new DashboardEvidenceCard(
                    displayName,
                    status,
                    keyFinding,
                    confidence,
                    sourceEvidence.size(),
                    lastCollected,
                    List.of()
            ));
        }

        return cards;
    }

    private RootCauseDashboardView buildRootCauseView(UUID caseId) {
        return new RootCauseDashboardView(
                "Deterministic analysis pending",
                "See evidence matrix",
                0.0,
                "LOW",
                List.of(),
                List.of("Review collected evidence", "Approve Jira preview"),
                List.of(),
                "Deterministic analysis"
        );
    }

    private RovoRcaDashboardView buildRovoRcaView(UUID caseId) {
        // Find latest ROVO_RCA evidence (sorted by created_at DESC)
        Optional<EvidenceEntity> rovoRcaEvidence = latestEvidence(caseId, EvidenceType.ROVO_RCA);

        if (rovoRcaEvidence.isEmpty()) {
            return RovoRcaDashboardView.notAvailable();
        }

        try {
            EvidenceEntity evidence = rovoRcaEvidence.get();
            String json = evidence.getContentText();
            
            // Try to parse as envelope first (new format)
            try {
                com.etiya.replayfix.model.RovoRcaEnvelope envelope = 
                        objectMapper.readValue(json, com.etiya.replayfix.model.RovoRcaEnvelope.class);
                
                com.fasterxml.jackson.databind.JsonNode sanitizedRawJson =
                        sanitizeJsonNode(envelope.rawRovoJson());
                com.fasterxml.jackson.databind.JsonNode sanitizedNormalizedJson =
                        sanitizeJsonNode(envelope.normalizedRovoJson());

                // Parse normalized JSON from envelope
                RovoRcaAnalysis analysis = parseRovoRcaAnalysis(
                        sanitizedNormalizedJson
                );
                
                return RovoRcaDashboardView.fromAnalysis(
                        analysis,
                        !envelope.normalizationWarnings().isEmpty(),
                        sanitizeList(envelope.normalizationWarnings()),
                        sanitizeText(envelope.rawHumanReport()),
                        sanitizedRawJson,
                        sanitizedNormalizedJson,
                        objectMapper.writeValueAsString(sanitizedRawJson),
                        envelope.importStatus(),
                        envelope.commentId(),
                        sanitizeText(envelope.commentAuthor()),
                        envelope.importedAt() != null ? envelope.importedAt().toString() : null
                );
            } catch (Exception envelopeEx) {
                // Fallback: try legacy format (direct RovoRcaAnalysis)
                log.debug("Not an envelope, trying legacy format: {}", envelopeEx.getMessage());
                com.fasterxml.jackson.databind.JsonNode legacyJson =
                        sanitizeJsonNode(objectMapper.readTree(json));
                RovoRcaAnalysis analysis = parseRovoRcaAnalysis(legacyJson);
                String sanitizedJson = objectMapper.writeValueAsString(legacyJson);
                
                boolean wasNormalized = detectIfNormalized(analysis);
                
                return RovoRcaDashboardView.fromAnalysis(
                        analysis,
                        wasNormalized,
                        List.of(),
                        "", // No human report in legacy format
                        legacyJson,
                        legacyJson,
                        sanitizedJson,
                        "IMPORTED",
                        null,
                        null,
                        null
                );
            }
        } catch (Exception e) {
            log.error("Failed to parse Rovo RCA for dashboard: caseId={}", caseId, e);
            return RovoRcaDashboardView.notAvailable();
        }
    }

    private RegressionTestHypothesisDashboardView buildRegressionTestHypothesisView(UUID caseId) {
        Optional<EvidenceEntity> evidence =
                latestEvidence(caseId, EvidenceType.REGRESSION_TEST_HYPOTHESIS);

        if (evidence.isEmpty()) {
            return RegressionTestHypothesisDashboardView.notAvailable();
        }

        try {
            RegressionTestHypothesis hypothesis = objectMapper.readValue(
                    evidence.get().getContentText(),
                    RegressionTestHypothesis.class
            );
            return RegressionTestHypothesisDashboardView.from(hypothesis);
        } catch (Exception exception) {
            log.error("Failed to parse regression test hypothesis for dashboard: caseId={}", caseId, exception);
            return RegressionTestHypothesisDashboardView.notAvailable();
        }
    }

    private FailingRegressionTestDraftDashboardView buildFailingRegressionTestDraftView(UUID caseId) {
        Optional<EvidenceEntity> evidence =
                latestEvidence(caseId, EvidenceType.FAILING_REGRESSION_TEST_DRAFT);

        if (evidence.isEmpty()) {
            return FailingRegressionTestDraftDashboardView.notAvailable();
        }

        try {
            FailingRegressionTestDraft draft = objectMapper.readValue(
                    evidence.get().getContentText(),
                    FailingRegressionTestDraft.class
            );
            return FailingRegressionTestDraftDashboardView.from(draft);
        } catch (Exception exception) {
            log.error("Failed to parse failing regression test draft for dashboard: caseId={}", caseId, exception);
            return FailingRegressionTestDraftDashboardView.notAvailable();
        }
    }

    private Optional<EvidenceEntity> latestEvidence(UUID caseId, EvidenceType evidenceType) {
        return evidenceRepository
                .findByCaseIdAndEvidenceType(caseId, evidenceType)
                .stream()
                .filter(item -> item.getCreatedAt() != null)
                .max(Comparator.comparing(EvidenceEntity::getCreatedAt));
    }

    private boolean detectIfNormalized(RovoRcaAnalysis analysis) {
        // Heuristic: if status is exactly "HYPOTHESIS" or confidence is exactly 0.0,
        // it might have been normalized with defaults
        if ("HYPOTHESIS".equals(analysis.status()) && 
            (analysis.confidence() == null || analysis.confidence() == 0.0)) {
            return true;
        }
        
        // Check if relatedJiraIssues have empty reasons (normalized from strings)
        if (analysis.relatedJiraIssues() != null) {
            for (var issue : analysis.relatedJiraIssues()) {
                if (issue.reason() != null && issue.reason().isEmpty()) {
                    return true;
                }
            }
        }
        
        return false;
    }

    private RovoRcaAnalysis parseRovoRcaAnalysis(
            com.fasterxml.jackson.databind.JsonNode value
    ) throws java.io.IOException {
        return objectMapper
                .readerFor(RovoRcaAnalysis.class)
                .without(
                        com.fasterxml.jackson.databind.DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES
                )
                .readValue(value);
    }

    private String sanitizeText(String value) {
        return evidenceSanitizer.sanitize(value);
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::sanitizeText)
                .toList();
    }

    private com.fasterxml.jackson.databind.JsonNode sanitizeJsonNode(
            com.fasterxml.jackson.databind.JsonNode value
    ) {
        if (value == null) {
            return null;
        }

        if (value.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode sanitized =
                    objectMapper.createObjectNode();

            value.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                if (isSensitiveField(fieldName)) {
                    sanitized.put(fieldName, "[REDACTED]");
                } else {
                    sanitized.set(
                            fieldName,
                            sanitizeJsonNode(entry.getValue())
                    );
                }
            });

            return sanitized;
        }

        if (value.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode sanitized =
                    objectMapper.createArrayNode();

            for (com.fasterxml.jackson.databind.JsonNode item : value) {
                sanitized.add(sanitizeJsonNode(item));
            }

            return sanitized;
        }

        if (value.isTextual()) {
            return objectMapper.getNodeFactory()
                    .textNode(sanitizeText(value.asText()));
        }

        return value.deepCopy();
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        return lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("secret");
    }

    private List<MissingEvidenceView> buildMissingEvidence(UUID caseId, WorkflowRunView workflow) {
        List<MissingEvidenceView> missing = new ArrayList<>();

        if (workflow != null) {
            for (WorkflowStepView step : workflow.steps()) {
                if ("SKIPPED".equals(step.status()) || "FAILED".equals(step.status())) {
                    missing.add(new MissingEvidenceView(
                            step.stepName(),
                            "FAILED".equals(step.status()) ? "HIGH" : "MEDIUM",
                            step.resultSummary() != null ? step.resultSummary() : "Step not executed",
                            step.evidenceSource(),
                            "Review step configuration"
                    ));
                }
            }
        }

        return missing;
    }

    private JiraEvidenceCommentPreview getLatestPreview(UUID caseId) {
        try {
            return previewService.createPreview(caseId);
        } catch (Exception e) {
            log.debug("No preview available for case {}: {}", caseId, e.getMessage());
            return null;
        }
    }

    private List<ApprovalRequestView> getApprovals(UUID caseId) {
        List<ApprovalRequestEntity> approvals = approvalRepository
                .findByCaseIdOrderByRequestedAtDesc(caseId);

        return approvals.stream()
                .map(this::toApprovalView)
                .toList();
    }

    private List<AuditEventView> getRecentAuditEvents(UUID caseId, int limit) {
        List<AuditEventEntity> events = auditRepository
                .findByCaseIdOrderByCreatedAtDesc(caseId);

        return events.stream()
                .limit(limit)
                .map(this::toAuditView)
                .toList();
    }

    private DashboardPolicyView buildPolicyView() {
        return new DashboardPolicyView(
                properties.getPolicy().isAllowJiraCommentWrite(),
                properties.getPolicy().isAllowGeneratedCodeWrite(),
                properties.getPolicy().isAllowTestExecution(),
                properties.getPolicy().isAllowGitPush(),
                properties.getPolicy().isAllowPullRequestCreation()
        );
    }

    private CaseListItemView toCaseListItem(ReplayCaseEntity caseEntity) {
        Optional<WorkflowRunEntity> latestWorkflow = workflowRunRepository
                .findFirstByCaseIdOrderByCreatedAtDesc(caseEntity.getId());

        String workflowStatus = latestWorkflow
                .map(w -> w.getStatus().name())
                .orElse("NO_WORKFLOW");

        return new CaseListItemView(
                caseEntity.getId(),
                caseEntity.getJiraKey(),
                "Incident: " + caseEntity.getJiraKey(),
                caseEntity.getTargetKey(),
                workflowStatus,
                null,
                caseEntity.getCreatedAt(),
                caseEntity.getUpdatedAt()
        );
    }

    private ApprovalRequestView toApprovalView(ApprovalRequestEntity approval) {
        return new ApprovalRequestView(
                approval.getId(),
                approval.getCaseId(),
                approval.getTargetType(),
                approval.getTargetEvidenceId(),
                approval.getTargetEvidenceType(),
                approval.getTargetEvidenceSource(),
                approval.getStatus(),
                approval.getRequestedBy(),
                approval.getRequestComment(),
                approval.getRequestedAt(),
                approval.getDecidedBy(),
                approval.getDecisionComment(),
                approval.getDecidedAt(),
                false,
                false,
                false
        );
    }

    private AuditEventView toAuditView(AuditEventEntity audit) {
        return new AuditEventView(
                audit.getId(),
                audit.getCaseId(),
                audit.getAction(),
                audit.getActor(),
                audit.getCreatedAt(),
                sanitizeAuditDetails(audit.getDetails())
        );
    }

    private String sanitizeAuditDetails(String details) {
        if (details == null) return null;
        if (details.length() > 200) {
            return details.substring(0, 200) + "...";
        }
        return details;
    }

    private String[][] getEvidenceSources() {
        return new String[][]{
                {"jira-issue", "Jira"},
                {"loki-log", "Loki"},
                {"tempo-trace", "Tempo"},
                {"repository-context", "Bitbucket"},
                {"jenkins-build", "Jenkins"},
                {"confluence-knowledge", "Confluence"},
                {"kubernetes-runtime", "Kubernetes"},
                {"jira-evidence-summary-preview", "ReplayFix Preview"}
        };
    }
}
