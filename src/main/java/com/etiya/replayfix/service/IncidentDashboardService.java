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

        List<DashboardEvidenceCard> cards = new ArrayList<>();

        for (EvidenceSourceGroup group : evidenceSourceGroups()) {
            List<EvidenceEntity> groupEvidence = allEvidence.stream()
                    .filter(item -> group.types().contains(item.getEvidenceType()))
                    .toList();

            cards.add(buildEvidenceCard(group, groupEvidence));
        }

        return cards;
    }

    private DashboardEvidenceCard buildEvidenceCard(
            EvidenceSourceGroup group,
            List<EvidenceEntity> evidence
    ) {
        if (evidence.isEmpty()) {
            return new DashboardEvidenceCard(
                    group.displayName(),
                    "UNAVAILABLE",
                    "No evidence collected",
                    "N/A",
                    0,
                    null,
                    List.of()
            );
        }

        EvidenceAvailability availability =
                determineDashboardAvailability(group, evidence);

        List<String> warnings =
                evidence.stream()
                        .map(this::weakEvidenceWarning)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

        EvidenceEntity latest = evidence.stream()
                .filter(item -> item.getCreatedAt() != null)
                .max(Comparator.comparing(EvidenceEntity::getCreatedAt))
                .orElse(evidence.get(evidence.size() - 1));

        return new DashboardEvidenceCard(
                group.displayName(),
                availability.name(),
                buildDashboardFinding(group, evidence),
                availability == EvidenceAvailability.CONFIRMED
                        ? "High"
                        : "Medium",
                evidence.size(),
                latest.getCreatedAt() != null
                        ? latest.getCreatedAt().toString()
                        : null,
                warnings
        );
    }

    private EvidenceAvailability determineDashboardAvailability(
            EvidenceSourceGroup group,
            List<EvidenceEntity> evidence
    ) {
        if (evidence.isEmpty()) {
            return EvidenceAvailability.UNAVAILABLE;
        }

        boolean weak = evidence.stream()
                .anyMatch(item -> isWeakDashboardEvidence(group, item));

        return weak
                ? EvidenceAvailability.PROBABLE
                : EvidenceAvailability.CONFIRMED;
    }

    private boolean isWeakDashboardEvidence(
            EvidenceSourceGroup group,
            EvidenceEntity evidence
    ) {
        EvidenceType type = evidence.getEvidenceType();

        if (group.displayName().equals("Loki")) {
            return hasZeroMetric(
                    evidence,
                    "matchedRowCount",
                    "matchedRows",
                    "matchedLogCount",
                    "logCount",
                    "resultCount"
            );
        }

        if (group.displayName().equals("Tempo")) {
            return hasZeroMetric(
                    evidence,
                    "foundTraceCount",
                    "traceCount",
                    "foundTraces"
            );
        }

        if (group.displayName().equals("Source Context")
                || type == EvidenceType.SOURCE_CONTEXT) {
            return hasZeroMetric(
                    evidence,
                    "matchedFileCount",
                    "scannedFileCount",
                    "fileCount"
            );
        }

        return false;
    }

    private boolean hasZeroMetric(EvidenceEntity evidence, String... fieldNames) {
        Optional<com.fasterxml.jackson.databind.JsonNode> json =
                readEvidenceJson(evidence);

        if (json.isEmpty()) {
            return false;
        }

        for (String fieldName : fieldNames) {
            Optional<Integer> value = findIntField(json.get(), fieldName);
            if (value.isPresent()) {
                return value.get() == 0;
            }
        }

        return false;
    }

    private Optional<Integer> findIntField(
            com.fasterxml.jackson.databind.JsonNode node,
            String fieldName
    ) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                return Optional.of(value.asInt());
            }

            Iterator<com.fasterxml.jackson.databind.JsonNode> children =
                    node.elements();
            while (children.hasNext()) {
                Optional<Integer> childValue =
                        findIntField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                Optional<Integer> childValue =
                        findIntField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private Optional<Double> findDoubleField(
            com.fasterxml.jackson.databind.JsonNode node,
            String fieldName
    ) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                return Optional.of(value.asDouble());
            }

            Iterator<com.fasterxml.jackson.databind.JsonNode> children =
                    node.elements();
            while (children.hasNext()) {
                Optional<Double> childValue =
                        findDoubleField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                Optional<Double> childValue =
                        findDoubleField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> findTextField(
            com.fasterxml.jackson.databind.JsonNode node,
            String fieldName
    ) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
            if (value != null) {
                if (value.isTextual()) {
                    return Optional.of(value.asText());
                }
                if (value.isNumber() || value.isBoolean()) {
                    return Optional.of(value.asText());
                }
                if (value.isArray() && !value.isEmpty()) {
                    com.fasterxml.jackson.databind.JsonNode first = value.get(0);
                    if (first.isTextual()) {
                        return Optional.of(first.asText());
                    }
                    if (first.isObject()) {
                        return findTextField(first, "statement")
                                .or(() -> findTextField(first, "reason"))
                                .or(() -> findTextField(first, "title"));
                    }
                }
            }

            Iterator<com.fasterxml.jackson.databind.JsonNode> children =
                    node.elements();
            while (children.hasNext()) {
                Optional<String> childValue =
                        findTextField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                Optional<String> childValue =
                        findTextField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private List<String> findStringList(
            com.fasterxml.jackson.databind.JsonNode node,
            String fieldName
    ) {
        Optional<com.fasterxml.jackson.databind.JsonNode> value =
                findField(node, fieldName);
        if (value.isEmpty() || !value.get().isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : value.get()) {
            if (item.isTextual()) {
                result.add(sanitizeText(item.asText()));
            } else if (item.isObject()) {
                findTextField(item, "statement")
                        .or(() -> findTextField(item, "reason"))
                        .or(() -> findTextField(item, "title"))
                        .map(this::sanitizeText)
                        .ifPresent(result::add);
            }
        }
        return result;
    }

    private Optional<com.fasterxml.jackson.databind.JsonNode> findField(
            com.fasterxml.jackson.databind.JsonNode node,
            String fieldName
    ) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
            if (value != null) {
                return Optional.of(value);
            }

            Iterator<com.fasterxml.jackson.databind.JsonNode> children =
                    node.elements();
            while (children.hasNext()) {
                Optional<com.fasterxml.jackson.databind.JsonNode> childValue =
                        findField(children.next(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                Optional<com.fasterxml.jackson.databind.JsonNode> childValue =
                        findField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private boolean isDisabledRootCause(
            com.fasterxml.jackson.databind.JsonNode node
    ) {
        Optional<String> status = findTextField(node, "status");
        if (status.isPresent()
                && "DISABLED".equalsIgnoreCase(status.get())) {
            return true;
        }

        com.fasterxml.jackson.databind.JsonNode disabled = node.get("disabled");
        if (disabled != null && disabled.isBoolean() && disabled.asBoolean()) {
            return true;
        }

        com.fasterxml.jackson.databind.JsonNode enabled = node.get("enabled");
        return enabled != null && enabled.isBoolean() && !enabled.asBoolean();
    }

    private Optional<com.fasterxml.jackson.databind.JsonNode> readEvidenceJson(
            EvidenceEntity evidence
    ) {
        String content = firstNonBlank(
                evidence.getContentText(),
                evidence.getBody()
        );

        if (content == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String weakEvidenceWarning(EvidenceEntity evidence) {
        EvidenceType type = evidence.getEvidenceType();

        if (type == EvidenceType.LOKI_LOG
                || type == EvidenceType.LOKI_QUERY_PLAN
                || type == EvidenceType.LOKI_CORRELATION_SIGNALS
                || type == EvidenceType.LOKI_SECOND_PASS) {
            if (hasZeroMetric(
                    evidence,
                    "matchedRowCount",
                    "matchedRows",
                    "matchedLogCount",
                    "logCount",
                    "resultCount"
            )) {
                return "Loki evidence exists but no matching rows were found.";
            }
        }

        if (type == EvidenceType.TEMPO_ENRICHMENT
                && hasZeroMetric(
                evidence,
                "foundTraceCount",
                "traceCount",
                "foundTraces"
        )) {
            return "Tempo evidence exists but no traces were found.";
        }

        if (type == EvidenceType.SOURCE_CONTEXT
                && hasZeroMetric(
                evidence,
                "matchedFileCount",
                "scannedFileCount",
                "fileCount"
        )) {
            return "Source context evidence exists but no matching files were found.";
        }

        return null;
    }

    private String buildDashboardFinding(
            EvidenceSourceGroup group,
            List<EvidenceEntity> evidence
    ) {
        String types = evidence.stream()
                .map(item -> item.getEvidenceType().name())
                .distinct()
                .collect(Collectors.joining(", "));

        return group.displayName()
                + " evidence available: "
                + types;
    }

    private RootCauseDashboardView buildRootCauseView(UUID caseId) {
        Optional<RootCauseSummary> rovoRootCause =
                buildRovoRootCauseSummary(caseId);
        if (rovoRootCause.isPresent()) {
            return rovoRootCause.get().toView();
        }

        Optional<RootCauseSummary> deterministicRootCause =
                buildEvidenceRootCauseSummary(
                        caseId,
                        EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                        "ReplayLab deterministic RCA"
                );
        if (deterministicRootCause.isPresent()) {
            return deterministicRootCause.get().toView();
        }

        Optional<RootCauseSummary> aiRootCause =
                buildEvidenceRootCauseSummary(
                        caseId,
                        EvidenceType.AI_ROOT_CAUSE,
                        "AI root-cause analysis"
                );
        if (aiRootCause.isPresent()) {
            return aiRootCause.get().toView();
        }

        return new RootCauseDashboardView(
                "Analysis pending",
                "See evidence matrix",
                0.0,
                "LOW",
                List.of(),
                List.of("Review collected evidence"),
                List.of(),
                "Analysis pending"
        );
    }

    private Optional<RootCauseSummary> buildRovoRootCauseSummary(UUID caseId) {
        Optional<EvidenceEntity> evidence =
                latestEvidence(caseId, EvidenceType.ROVO_RCA);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        Optional<com.fasterxml.jackson.databind.JsonNode> json =
                readEvidenceJson(evidence.get());
        if (json.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> rootCause =
                findTextField(json.get(), "probableRootCause");
        if (rootCause.isEmpty() || rootCause.get().isBlank()) {
            return Optional.empty();
        }

        double confidence = findDoubleField(json.get(), "confidence")
                .orElse(evidence.get().getConfidence() != null
                        ? evidence.get().getConfidence()
                        : 0.0);

        return Optional.of(new RootCauseSummary(
                sanitizeText(rootCause.get()),
                findTextField(json.get(), "impactedComponent")
                        .map(this::sanitizeText)
                        .orElse("See evidence matrix"),
                confidence,
                findStringList(json.get(), "competingHypotheses"),
                findStringList(json.get(), "minimumFixDirection"),
                findStringList(json.get(), "regressionTestHypothesis"),
                "Rovo RCA enriched analysis"
        ));
    }

    private Optional<RootCauseSummary> buildEvidenceRootCauseSummary(
            UUID caseId,
            EvidenceType type,
            String analysisType
    ) {
        Optional<EvidenceEntity> evidence = latestEvidence(caseId, type);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        Optional<com.fasterxml.jackson.databind.JsonNode> json =
                readEvidenceJson(evidence.get());
        if (json.isEmpty() || isDisabledRootCause(json.get())) {
            return Optional.empty();
        }

        Optional<String> rootCause = findTextField(json.get(), "probableCause")
                .or(() -> findTextField(json.get(), "probableRootCause"))
                .or(() -> findTextField(json.get(), "rootCause"))
                .or(() -> findTextField(json.get(), "summary"));
        if (rootCause.isEmpty() || rootCause.get().isBlank()) {
            return Optional.empty();
        }

        double confidence = findDoubleField(json.get(), "confidence")
                .orElse(evidence.get().getConfidence() != null
                        ? evidence.get().getConfidence()
                        : 0.0);

        return Optional.of(new RootCauseSummary(
                sanitizeText(rootCause.get()),
                findTextField(json.get(), "impactedComponent")
                        .map(this::sanitizeText)
                        .orElse("See evidence matrix"),
                confidence,
                findStringList(json.get(), "competingHypotheses"),
                findStringList(json.get(), "minimumFixDirection"),
                findStringList(json.get(), "regressionTestHypothesis"),
                analysisType
        ));
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

    private List<EvidenceSourceGroup> evidenceSourceGroups() {
        return List.of(
                new EvidenceSourceGroup(
                        "Jira",
                        Set.of(EvidenceType.JIRA_ISSUE)
                ),
                new EvidenceSourceGroup(
                        "Bitbucket",
                        Set.of(
                                EvidenceType.REPOSITORY_RESOLUTION,
                                EvidenceType.INCIDENT_VERSION
                        )
                ),
                new EvidenceSourceGroup(
                        "Jenkins",
                        Set.of(EvidenceType.JENKINS_BUILD_CONTEXT)
                ),
                new EvidenceSourceGroup(
                        "Loki",
                        Set.of(
                                EvidenceType.LOKI_QUERY_PLAN,
                                EvidenceType.LOKI_LOG,
                                EvidenceType.LOKI_LOGS,
                                EvidenceType.LOKI_CORRELATION_SIGNALS,
                                EvidenceType.LOKI_SECOND_PASS,
                                EvidenceType.INCIDENT_TIMELINE
                        )
                ),
                new EvidenceSourceGroup(
                        "Tempo",
                        Set.of(EvidenceType.TEMPO_ENRICHMENT)
                ),
                new EvidenceSourceGroup(
                        "Source Context",
                        Set.of(EvidenceType.SOURCE_CONTEXT)
                ),
                new EvidenceSourceGroup(
                        "ReplayLab",
                        Set.of(
                                EvidenceType.AI_INPUT_BUNDLE,
                                EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                                EvidenceType.AI_ROOT_CAUSE,
                                EvidenceType.ROVO_RCA,
                                EvidenceType.REGRESSION_TEST_HYPOTHESIS,
                                EvidenceType.FAILING_REGRESSION_TEST_DRAFT
                        )
                )
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record EvidenceSourceGroup(
            String displayName,
            Set<EvidenceType> types
    ) {
    }

    private record RootCauseSummary(
            String probableRootCause,
            String impactedComponent,
            double confidence,
            List<String> competingHypotheses,
            List<String> recommendedFixDirection,
            List<String> regressionTestHypothesis,
            String analysisType
    ) {
        RootCauseDashboardView toView() {
            return new RootCauseDashboardView(
                    probableRootCause,
                    impactedComponent,
                    confidence,
                    confidence >= 0.7
                            ? "HIGH"
                            : confidence >= 0.3 ? "MEDIUM" : "LOW",
                    competingHypotheses != null
                            ? competingHypotheses
                            : List.of(),
                    recommendedFixDirection != null
                            && !recommendedFixDirection.isEmpty()
                            ? recommendedFixDirection
                            : List.of("Review current RCA evidence"),
                    regressionTestHypothesis != null
                            ? regressionTestHypothesis
                            : List.of(),
                    analysisType
            );
        }
    }
}
