package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabAgentEvent;
import com.etiya.replaylab.api.dto.ReplayLabEnvironmentBlueprintRequest;
import com.etiya.replaylab.api.dto.ReplayLabEnvironmentBlueprintResponse;
import com.etiya.replaylab.api.dto.ReplayLabEvidenceDetail;
import com.etiya.replaylab.api.dto.ReplayLabFinalRemediationBriefResponse;
import com.etiya.replaylab.api.dto.ReplayLabHumanEvidenceRequest;
import com.etiya.replaylab.api.dto.ReplayLabLiveDemoStateResponse;
import com.etiya.replaylab.api.dto.ReplayLabRcaResponse;
import com.etiya.replaylab.api.dto.ReplayLabTokenUsageEstimateResponse;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ReplayLabLiveDemoService {

    private static final String DEFECT_KEY = "FIZZMS-10228";
    private static final String DEFECT_TITLE =
            "Region, Tax_Info and Timezone Mismatch when Creating or Updating Billing Account at i2i";
    private static final String TARGET_KEY = "backend";
    private static final String ENVIRONMENT = "test2";
    private static final String SOURCE_HUMAN = "Human Evidence";
    private static final String SOURCE_ENVIRONMENT_PLAN = "ReplayLab Live Demo Environment Blueprint";
    private static final String SOURCE_ENVIRONMENT_SKIP = "ReplayLab Live Demo Environment Skip";
    private static final String SOURCE_RCA = "ReplayLab Live Demo RCA";

    private static final Pattern SENSITIVE_WORDS = Pattern.compile(
            "(?i)authorization|bearer|cookie|password|secret|api[_-]?key|private[_-]?key"
    );
    private static final Pattern CREDENTIAL_IN_URL = Pattern.compile("(?i)(https?://)[^/@\\s]+@");

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ReplayLabDoraImpactScoreboardService doraImpactService;
    private final ReplayLabRemediationReadinessService readinessService;
    private final ReplayLabFinalRemediationBriefService finalBriefService;
    private final EvidenceSanitizer evidenceSanitizer;
    private final ObjectMapper objectMapper;

    public ReplayLabLiveDemoService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayLabDoraImpactScoreboardService doraImpactService,
            ReplayLabRemediationReadinessService readinessService,
            ReplayLabFinalRemediationBriefService finalBriefService,
            EvidenceSanitizer evidenceSanitizer,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.doraImpactService = doraImpactService;
        this.readinessService = readinessService;
        this.finalBriefService = finalBriefService;
        this.evidenceSanitizer = evidenceSanitizer;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReplayLabLiveDemoStateResponse state(UUID caseId) {
        ReplayCaseEntity replayCase = findCase(caseId);
        return buildState(replayCase, false);
    }

    @Transactional
    public ReplayLabLiveDemoStateResponse start(UUID caseId) {
        ReplayCaseEntity replayCase = findOrCreateCase(caseId);
        return buildState(replayCase, false);
    }

    @Transactional
    public ReplayLabLiveDemoStateResponse collectEvidence(UUID caseId) {
        ReplayCaseEntity replayCase = findOrCreateCase(caseId);
        for (DemoEvidenceSeed seed : demoEvidenceSeeds(replayCase)) {
            if (latestBySource(replayCase.getId(), seed.source()).isEmpty()) {
                saveEvidence(
                        replayCase.getId(),
                        seed.type(),
                        seed.source(),
                        seed.summary(),
                        seed.payload(),
                        seed.confidenceValue()
                );
            }
        }
        return buildState(replayCase, false);
    }

    @Transactional
    public ReplayLabRcaResponse generateRca(UUID caseId) {
        ReplayCaseEntity replayCase = findOrCreateCase(caseId);
        ReplayLabRcaResponse response = rcaResponse();
        if (latestBySource(replayCase.getId(), SOURCE_RCA).isEmpty()) {
            saveEvidence(
                    replayCase.getId(),
                    EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                    SOURCE_RCA,
                    response.probableRootCause(),
                    Map.of(
                            "status", response.status(),
                            "confidence", response.confidence(),
                            "probableRootCause", response.probableRootCause(),
                            "recommendedNextAction", response.recommendedNextAction()
                    ),
                    0.55
            );
        }
        return response;
    }

    @Transactional
    public ReplayLabLiveDemoStateResponse addHumanEvidence(
            UUID caseId,
            ReplayLabHumanEvidenceRequest request
    ) {
        ReplayCaseEntity replayCase = findOrCreateCase(caseId);
        int nextNumber = humanEvidence(replayCase.getId()).size() + 1;
        String title = firstNonBlank(request == null ? "" : request.title(), "Human evidence #" + nextNumber);
        String sourceType = firstNonBlank(request == null ? "" : request.sourceType(), "Other");
        String notes = sanitize(request == null ? "" : request.notes());
        String url = sanitizeUrl(request == null ? "" : request.url());
        saveEvidence(
                replayCase.getId(),
                EvidenceType.REPLAY_OUTPUT,
                SOURCE_HUMAN,
                title,
                Map.of(
                        "sourceType", sanitize(sourceType),
                        "title", sanitize(title),
                        "notes", notes,
                        "url", url
                ),
                0.8
        );
        return buildState(replayCase, false);
    }

    @Transactional(readOnly = true)
    public List<ReplayLabEvidenceDetail> evidence(UUID caseId) {
        ReplayCaseEntity replayCase = findCase(caseId);
        return evidenceDetails(replayCase, false);
    }

    public ReplayLabTokenUsageEstimateResponse tokenUsage(UUID caseId) {
        return tokenUsage();
    }

    @Transactional
    public ReplayLabEnvironmentBlueprintResponse planEnvironment(
            UUID caseId,
            ReplayLabEnvironmentBlueprintRequest request
    ) {
        ReplayCaseEntity replayCase = findOrCreateCase(caseId);
        ReplayLabEnvironmentBlueprintResponse response = environmentBlueprint(
                replayCase.getId(),
                request,
                false
        );
        saveEvidence(
                replayCase.getId(),
                EvidenceType.REPLAY_OUTPUT,
                SOURCE_ENVIRONMENT_PLAN,
                "Dry-run replay environment blueprint prepared",
                toMap(response),
                0.75
        );
        return response;
    }

    @Transactional
    public ReplayLabLiveDemoStateResponse skipEnvironment(UUID caseId) {
        ReplayCaseEntity replayCase = findOrCreateCase(caseId);
        saveEvidence(
                replayCase.getId(),
                EvidenceType.REPLAY_OUTPUT,
                SOURCE_ENVIRONMENT_SKIP,
                "Replay environment skipped by user",
                Map.of(
                        "status", "SKIPPED",
                        "message", "Replay environment skipped by user",
                        "provisioningExecuted", false
                ),
                0.8
        );
        return buildState(replayCase, false);
    }

    @Transactional(readOnly = true)
    public ReplayLabLiveDemoStateResponse finalState(UUID caseId) {
        ReplayCaseEntity replayCase = findCase(caseId);
        return buildState(replayCase, true);
    }

    private ReplayLabLiveDemoStateResponse buildState(
            ReplayCaseEntity replayCase,
            boolean finalView
    ) {
        List<EvidenceEntity> allEvidence = orderedEvidence(replayCase.getId());
        List<ReplayLabEvidenceDetail> demoEvidence = evidenceDetails(replayCase, finalView);
        List<ReplayLabEvidenceDetail> humanEvidence = humanEvidenceDetails(replayCase);
        List<String> completedSteps = completedSteps(replayCase, allEvidence, finalView);
        List<String> unlockedSteps = unlockedSteps(completedSteps, allEvidence, finalView);
        ReplayLabFinalRemediationBriefResponse brief = finalBriefService.response(replayCase, allEvidence);
        ReplayLabEnvironmentBlueprintResponse environmentBlueprint = latestEnvironmentBlueprint(replayCase)
                .orElse(finalView ? environmentBlueprint(replayCase.getId(), null, true) : null);
        ReplayLabRcaResponse rca = hasRca(allEvidence) || finalView ? rcaResponse() : null;

        return new ReplayLabLiveDemoStateResponse(
                replayCase.getId(),
                firstNonBlank(replayCase.getJiraKey(), DEFECT_KEY),
                DEFECT_TITLE,
                firstNonBlank(replayCase.getTargetKey(), TARGET_KEY),
                firstNonBlank(replayCase.getEnvironment(), ENVIRONMENT),
                currentStep(completedSteps, finalView),
                completedSteps,
                unlockedSteps,
                agentEvents(allEvidence, completedSteps, finalView),
                demoEvidence,
                humanEvidence,
                tokenUsage(),
                doraImpactService.response(replayCase),
                readinessService.response(replayCase, allEvidence),
                environmentBlueprint,
                preflightSummary(allEvidence, finalView),
                targetedPrPreview(allEvidence, finalView),
                jiraTestCasePreview(finalView || completedSteps.contains("ENVIRONMENT")),
                rca,
                brief.markdown(),
                guardrails(),
                fallbackUsed(demoEvidence, allEvidence, finalView),
                warnings(demoEvidence),
                nextActions(completedSteps, finalView)
        );
    }

    private ReplayCaseEntity findCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "ReplayLab case not found: " + caseId));
    }

    private ReplayCaseEntity findOrCreateCase(UUID caseId) {
        Optional<ReplayCaseEntity> existing = caseRepository.findById(caseId);
        if (existing.isPresent()) {
            ReplayCaseEntity replayCase = existing.get();
            boolean changed = false;
            if (isBlank(replayCase.getJiraKey())) {
                replayCase.setJiraKey(DEFECT_KEY);
                changed = true;
            }
            if (isBlank(replayCase.getTargetKey())) {
                replayCase.setTargetKey(TARGET_KEY);
                changed = true;
            }
            if (isBlank(replayCase.getEnvironment())) {
                replayCase.setEnvironment(ENVIRONMENT);
                changed = true;
            }
            if (!replayCase.isSynthetic()) {
                replayCase.setSynthetic(true);
                changed = true;
            }
            return changed ? caseRepository.save(replayCase) : replayCase;
        }

        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey(DEFECT_KEY);
        replayCase.setTargetKey(TARGET_KEY);
        replayCase.setEnvironment(ENVIRONMENT);
        replayCase.setSynthetic(true);
        replayCase.setStatus(ReplayCaseStatus.NEW);
        return caseRepository.save(replayCase);
    }

    private List<DemoEvidenceSeed> demoEvidenceSeeds(ReplayCaseEntity replayCase) {
        String caseKey = firstNonBlank(replayCase.getJiraKey(), DEFECT_KEY);
        String target = firstNonBlank(replayCase.getTargetKey(), TARGET_KEY);
        String environment = firstNonBlank(replayCase.getEnvironment(), ENVIRONMENT);
        return List.of(
                new DemoEvidenceSeed(
                        EvidenceType.JIRA_ISSUE,
                        "Jira",
                        "Defect " + caseKey + " read in preview-safe mode.",
                        Map.of(
                                "jiraIssue", caseKey,
                                "title", DEFECT_TITLE,
                                "writeExecuted", false
                        ),
                        0.9
                ),
                new DemoEvidenceSeed(
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        "Jenkins",
                        "Build context checked without triggering a job.",
                        Map.of(
                                "environment", environment,
                                "triggerExecuted", false,
                                "latestBuildContext", "demo-safe"
                        ),
                        0.65
                ),
                new DemoEvidenceSeed(
                        EvidenceType.REPOSITORY_RESOLUTION,
                        "Bitbucket",
                        "Repository DCE/" + target + " resolved for preview.",
                        Map.of(
                                "repository", "DCE/" + target,
                                "branchWriteExecuted", false,
                                "pullRequestCreated", false
                        ),
                        0.8
                ),
                new DemoEvidenceSeed(
                        EvidenceType.LOKI_QUERY_PLAN,
                        "Loki",
                        "Log query plan prepared; runtime log access may be limited.",
                        Map.of(
                                "queryMode", "DRY_RUN",
                                "rawPayloadReturned", false
                        ),
                        0.45
                ),
                new DemoEvidenceSeed(
                        EvidenceType.TEMPO_TRACE,
                        "Tempo",
                        "Trace availability checked; trace evidence is limited in demo mode.",
                        Map.of(
                                "traceAvailability", "LIMITED",
                                "rawTraceReturned", false
                        ),
                        0.4
                ),
                new DemoEvidenceSeed(
                        EvidenceType.SOURCE_CONTEXT,
                        "Source Context",
                        "Create/update billing account flow matched as the target source area.",
                        Map.of(
                                "targetKey", target,
                                "sourceArea", "billing account create/update flow",
                                "checkoutMutated", false
                        ),
                        0.7
                ),
                new DemoEvidenceSeed(
                        EvidenceType.ROVO_RCA,
                        "Rovo",
                        "RCA roundtrip evidence prepared with confidence boundaries.",
                        Map.of(
                                "status", "HYPOTHESIS",
                                "confidence", "EVIDENCE_LIMITED"
                        ),
                        0.55
                ),
                new DemoEvidenceSeed(
                        EvidenceType.PULL_REQUEST,
                        "AGENTS.md",
                        "AGENTS preflight rules summarized; no write action executed.",
                        Map.of(
                                "reviewStatus", "PREVIEW_READY",
                                "rulesLoaded", true,
                                "blockerViolationCount", 0,
                                "writeExecuted", false
                        ),
                        0.75
                )
        );
    }

    private EvidenceEntity saveEvidence(
            UUID caseId,
            EvidenceType type,
            String source,
            String summary,
            Map<String, Object> payload,
            double confidence
    ) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setCaseId(caseId);
        evidence.setEvidenceType(type);
        evidence.setSource(source);
        evidence.setContentText(toJson(sanitizeObject(payload)));
        evidence.setBody(sanitize(summary));
        evidence.setConfidence(confidence);
        evidence.setSanitized(true);
        evidence.setCreatedAt(Instant.now());
        return evidenceRepository.save(evidence);
    }

    private List<ReplayLabEvidenceDetail> evidenceDetails(
            ReplayCaseEntity replayCase,
            boolean includeFallback
    ) {
        List<ReplayLabEvidenceDetail> details = new ArrayList<>();
        for (DemoEvidenceSeed seed : demoEvidenceSeeds(replayCase)) {
            Optional<EvidenceEntity> existing = latestBySource(replayCase.getId(), seed.source());
            details.add(existing
                    .map(item -> detailFromEvidence(item, seed.source(), "COLLECTED", List.of()))
                    .orElseGet(() -> fallbackDetail(seed, includeFallback)));
        }
        return List.copyOf(details);
    }

    private List<ReplayLabEvidenceDetail> humanEvidenceDetails(ReplayCaseEntity replayCase) {
        return humanEvidence(replayCase.getId()).stream()
                .map(item -> detailFromEvidence(item, SOURCE_HUMAN, "HUMAN_PROVIDED", List.of()))
                .toList();
    }

    private List<EvidenceEntity> humanEvidence(UUID caseId) {
        return orderedEvidence(caseId).stream()
                .filter(item -> SOURCE_HUMAN.equals(item.getSource()))
                .toList();
    }

    private ReplayLabEvidenceDetail fallbackDetail(
            DemoEvidenceSeed seed,
            boolean includeFallback
    ) {
        String status = includeFallback ? "DRY_RUN" : "UNAVAILABLE";
        String confidence = seed.confidenceValue() >= 0.75
                ? "MEDIUM"
                : "EVIDENCE_LIMITED";
        List<String> limitations = includeFallback
                ? List.of("Deterministic demo-safe fallback; live integration evidence was not found in the case store.")
                : List.of("Evidence has not been collected for this live demo session yet.");
        return new ReplayLabEvidenceDetail(
                "fallback-" + seed.source().toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                seed.source(),
                status,
                sanitize(seed.summary()),
                includeFallback ? 1 : 0,
                confidence,
                Instant.EPOCH,
                "",
                fields(seed.payload()),
                limitations,
                true
        );
    }

    private ReplayLabEvidenceDetail detailFromEvidence(
            EvidenceEntity evidence,
            String source,
            String status,
            List<String> extraLimitations
    ) {
        Map<String, Object> payload = parseMap(evidence);
        List<String> limitations = new ArrayList<>(extraLimitations);
        if (isEvidenceLimited(source, evidence)) {
            limitations.add("Evidence is sufficient for demo guidance but not enough to claim production certainty.");
        }
        String confidence = confidence(evidence.getConfidence(), source);
        return new ReplayLabEvidenceDetail(
                String.valueOf(evidence.getId()),
                source,
                status,
                firstNonBlank(sanitize(evidence.getBody()), sanitize(string(payload.get("summary"))), source + " evidence collected."),
                payload.isEmpty() ? 1 : payload.size(),
                confidence,
                evidence.getCreatedAt() == null ? Instant.EPOCH : evidence.getCreatedAt(),
                sanitizeUrl(string(payload.get("url"))),
                fields(payload),
                List.copyOf(limitations),
                true
        );
    }

    private List<ReplayLabEvidenceDetail.Field> fields(Map<String, Object> payload) {
        return sanitizeObject(payload).entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .limit(8)
                .map(entry -> new ReplayLabEvidenceDetail.Field(
                        humanize(entry.getKey()),
                        sanitize(string(entry.getValue()))
                ))
                .toList();
    }

    private List<EvidenceEntity> orderedEvidence(UUID caseId) {
        return evidenceRepository.findByCaseId(caseId).stream()
                .sorted(Comparator.comparing(EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private Optional<EvidenceEntity> latestBySource(UUID caseId, String source) {
        return evidenceRepository.findByCaseId(caseId).stream()
                .filter(item -> source.equals(item.getSource()))
                .max(Comparator.comparing(EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private ReplayLabTokenUsageEstimateResponse tokenUsage() {
        return new ReplayLabTokenUsageEstimateResponse(
                "ESTIMATE",
                8420,
                1250,
                4300,
                2100,
                1800,
                1470,
                9670,
                List.of("Usage is estimated for demo visibility.")
        );
    }

    private ReplayLabEnvironmentBlueprintResponse environmentBlueprint(
            UUID caseId,
            ReplayLabEnvironmentBlueprintRequest request,
            boolean fallback
    ) {
        List<String> required = safeList(request == null ? null : request.requiredComponents());
        if (required.isEmpty()) {
            required = List.of(
                    "backend",
                    "Jenkins build context",
                    "Bitbucket source revision",
                    "ReplayLab evidence store",
                    "AGENTS rule set"
            );
        }
        List<String> optional = safeList(request == null ? null : request.optionalComponents());
        if (optional.isEmpty()) {
            optional = List.of("Customer UI", "WSO2 integration", "Camunda workflow");
        }
        return new ReplayLabEnvironmentBlueprintResponse(
                caseId,
                true,
                fallback ? "BLUEPRINT_READY_FALLBACK" : "BLUEPRINT_READY",
                required,
                optional,
                List.of(
                        "ReplayLab evidence store",
                        "Bitbucket source revision",
                        "backend",
                        "Jenkins build context",
                        "AGENTS rule set"
                ),
                "DRY_RUN_ONLY",
                List.of(
                        "backend health endpoint reachable before approved validation",
                        "Jenkins context available without trigger",
                        "AGENTS rules loaded before PR preview"
                ),
                List.of(
                        "Mock customer account profile response",
                        "Mock tax profile response",
                        "Mock timezone lookup response"
                ),
                List.of(
                        "Synthetic billing account fixture",
                        "No production payload replayed",
                        "No credentialed service account required for dry run"
                ),
                "REQUIRED_BEFORE_PROVISIONING",
                true,
                false,
                List.of(
                        "No infrastructure changes executed",
                        "Provisioning requires approval",
                        "No production payload replayed"
                ),
                List.of(
                        "Analyzing impacted components",
                        "Resolving dependency order",
                        "Checking health requirements",
                        "Preparing dry-run environment blueprint",
                        "Approval required before provisioning"
                )
        );
    }

    private Optional<ReplayLabEnvironmentBlueprintResponse> latestEnvironmentBlueprint(ReplayCaseEntity replayCase) {
        Optional<EvidenceEntity> plan = latestBySource(replayCase.getId(), SOURCE_ENVIRONMENT_PLAN);
        if (plan.isPresent()) {
            try {
                return Optional.of(objectMapper.readValue(
                        plan.get().getContentText(),
                        ReplayLabEnvironmentBlueprintResponse.class
                ));
            } catch (Exception ignored) {
                return Optional.of(environmentBlueprint(replayCase.getId(), null, true));
            }
        }
        if (latestBySource(replayCase.getId(), SOURCE_ENVIRONMENT_SKIP).isPresent()) {
            return Optional.of(new ReplayLabEnvironmentBlueprintResponse(
                    replayCase.getId(),
                    true,
                    "SKIPPED",
                    List.of(),
                    List.of(),
                    List.of(),
                    "SKIPPED_BY_USER",
                    List.of(),
                    List.of(),
                    List.of(),
                    "NOT_APPLICABLE",
                    true,
                    false,
                    List.of("No infrastructure changes executed", "Environment provisioning skipped by user"),
                    List.of("Replay environment skipped by user")
            ));
        }
        return Optional.empty();
    }

    private ReplayLabRcaResponse rcaResponse() {
        return new ReplayLabRcaResponse(
                "HYPOTHESIS",
                "EVIDENCE_LIMITED",
                "Region, tax_info and timezone consistency should be validated across the billing account create/update flow.",
                List.of(
                        "Defect key FIZZMS-10228 targets the backend billing account flow.",
                        "The reported mismatch concerns region, tax_info and timezone fields."
                ),
                List.of(
                        "Create and update paths may apply different normalization or defaulting rules.",
                        "A downstream profile lookup may return values that are accepted without cross-field validation."
                ),
                List.of(
                        "Runtime logs that show the exact request/response field values.",
                        "Trace spans that identify the service boundary where mismatch first appears.",
                        "The deployed commit and source checkout comparison for the affected environment."
                ),
                "Run AGENTS preflight and prepare targeted regression coverage."
        );
    }

    private Map<String, Object> preflightSummary(
            List<EvidenceEntity> evidence,
            boolean includeFallback
    ) {
        Optional<Map<String, Object>> existing = evidence.stream()
                .filter(item -> "AGENTS.md".equals(item.getSource()) || item.getEvidenceType() == EvidenceType.PULL_REQUEST)
                .map(this::parseMap)
                .filter(value -> !value.isEmpty())
                .findFirst();
        if (existing.isPresent()) {
            return limited(existing.get(), List.of(
                    "reviewStatus",
                    "rulesLoaded",
                    "blockerViolationCount",
                    "blockers",
                    "warnings",
                    "writeExecuted"
            ));
        }
        if (!includeFallback) {
            return Map.of();
        }
        return Map.of(
                "status", "PREVIEW_READY",
                "rulesLoaded", true,
                "blockerViolationCount", 0,
                "approvalRequired", true,
                "writeExecuted", false,
                "guardrails", List.of("No write action executed", "Human review required before PR creation")
        );
    }

    private Map<String, Object> targetedPrPreview(
            List<EvidenceEntity> evidence,
            boolean includeFallback
    ) {
        Optional<Map<String, Object>> existing = evidence.stream()
                .filter(item -> item.getEvidenceType() == EvidenceType.PULL_REQUEST)
                .map(this::parseMap)
                .filter(value -> value.containsKey("filePath") || value.containsKey("previewOnly"))
                .findFirst();
        if (existing.isPresent()) {
            return limited(existing.get(), List.of(
                    "created",
                    "previewOnly",
                    "filePath",
                    "changeMode",
                    "bugfixBranch",
                    "integrationBranch",
                    "pullRequestUrl",
                    "blockers",
                    "warnings"
            ));
        }
        if (!includeFallback) {
            return Map.of();
        }
        return Map.of(
                "status", "PREVIEW_READY",
                "previewOnly", true,
                "created", false,
                "changeMode", "TARGETED_REGRESSION_AND_GUARD_FIX",
                "filePath", "backend/src/test/java/.../BillingAccountRegionTaxTimezoneTest.java",
                "approvalRequired", true,
                "guardrails", List.of("No PR created in preview mode", "No direct push to target branch")
        );
    }

    private Map<String, Object> jiraTestCasePreview(boolean includePreview) {
        if (!includePreview) {
            return Map.of();
        }
        return Map.of(
                "status", "PREVIEW_READY",
                "testCaseTitle", "Validate region, tax_info and timezone consistency for FIZZMS-10228",
                "purpose", "Prevent mismatch when creating or updating Billing Account at i2i.",
                "approvalRequired", true,
                "jiraWriteExecuted", false,
                "guardrails", List.of(
                        "No Jira test case created in preview mode",
                        "Approval required before Jira write"
                )
        );
    }

    private List<String> completedSteps(
            ReplayCaseEntity replayCase,
            List<EvidenceEntity> evidence,
            boolean finalView
    ) {
        if (finalView) {
            return List.of("START", "EVIDENCE", "RCA", "ENVIRONMENT", "PREFLIGHT", "PR_PREVIEW", "JIRA_PREVIEW", "BRIEF");
        }
        List<String> completed = new ArrayList<>();
        completed.add("START");
        if (hasDemoEvidence(evidence)) {
            completed.add("EVIDENCE");
        }
        if (hasRca(evidence)) {
            completed.add("RCA");
        }
        if (hasEnvironmentDecision(evidence)) {
            completed.add("ENVIRONMENT");
        }
        if (hasAgentsEvidence(evidence)) {
            completed.add("PREFLIGHT");
        }
        if (hasTargetedPrPreview(evidence)) {
            completed.add("PR_PREVIEW");
        }
        if (hasJiraPreview(evidence)) {
            completed.add("JIRA_PREVIEW");
        }
        return List.copyOf(completed);
    }

    private List<String> unlockedSteps(
            List<String> completedSteps,
            List<EvidenceEntity> evidence,
            boolean finalView
    ) {
        if (finalView) {
            return List.of("START", "EVIDENCE", "RCA", "ENVIRONMENT", "PREFLIGHT", "PR_PREVIEW", "JIRA_PREVIEW", "BRIEF");
        }
        List<String> unlocked = new ArrayList<>();
        unlocked.add("START");
        unlocked.add("EVIDENCE");
        if (completedSteps.contains("EVIDENCE")) {
            unlocked.add("RCA");
        }
        if (completedSteps.contains("RCA")) {
            unlocked.add("ENVIRONMENT");
        }
        if (completedSteps.contains("ENVIRONMENT")) {
            unlocked.add("PREFLIGHT");
        }
        if (hasAgentsEvidence(evidence)) {
            unlocked.add("PR_PREVIEW");
        }
        if (hasTargetedPrPreview(evidence)) {
            unlocked.add("JIRA_PREVIEW");
        }
        return List.copyOf(unlocked);
    }

    private String currentStep(List<String> completedSteps, boolean finalView) {
        if (finalView) {
            return "BRIEF";
        }
        if (!completedSteps.contains("EVIDENCE")) {
            return "START";
        }
        if (!completedSteps.contains("RCA")) {
            return "EVIDENCE";
        }
        if (!completedSteps.contains("ENVIRONMENT")) {
            return "RCA";
        }
        if (!completedSteps.contains("PREFLIGHT")) {
            return "ENVIRONMENT";
        }
        if (!completedSteps.contains("PR_PREVIEW")) {
            return "PREFLIGHT";
        }
        if (!completedSteps.contains("JIRA_PREVIEW")) {
            return "PR_PREVIEW";
        }
        return "BRIEF";
    }

    private List<ReplayLabAgentEvent> agentEvents(
            List<EvidenceEntity> evidence,
            List<String> completedSteps,
            boolean finalView
    ) {
        List<ReplayLabAgentEvent> events = new ArrayList<>();
        Instant base = evidence.stream()
                .map(EvidenceEntity::getCreatedAt)
                .filter(value -> value != null)
                .findFirst()
                .orElse(Instant.parse("2026-06-26T10:00:00Z"));
        events.add(event(base, 0, "RCA", "DONE", "ReplayLab Live Demo Orchestrator initialized", ""));
        if (completedSteps.contains("EVIDENCE") || finalView) {
            events.add(event(base, 3, "Jira", "DONE", "Reading defect FIZZMS-10228", DEFECT_TITLE));
            events.add(event(base, 6, "Bitbucket", "DONE", "Resolving repository DCE/backend", "Preview-only repository context"));
            events.add(event(base, 9, "Jenkins", "LIMITED", "Checking build context", "No Jenkins job was triggered"));
            events.add(event(base, 12, "Source", "DONE", "Matching deployed commit with checkout", "Source comparison planned"));
            events.add(event(base, 15, "Loki", "LIMITED", "Planning evidence queries", "No raw production logs returned"));
            events.add(event(base, 18, "Tempo", "LIMITED", "Checking trace availability", "Trace access is evidence-limited"));
            events.add(event(base, 21, "Rovo", "DONE", "Preparing RCA roundtrip evidence", "Confidence boundaries preserved"));
        }
        if (completedSteps.contains("RCA") || finalView) {
            events.add(event(base, 30, "RCA", "DONE", "Generated hypothesis-level RCA", "Evidence-limited hypothesis"));
        }
        if (hasSource(evidence, SOURCE_ENVIRONMENT_PLAN) || finalView) {
            events.add(event(base, 40, "Environment", "DONE", "Prepared dry-run replay environment blueprint", "No provisioning executed"));
        }
        if (hasSource(evidence, SOURCE_ENVIRONMENT_SKIP)) {
            events.add(event(base, 40, "Environment", "SKIPPED", "Replay environment skipped by user", "AGENTS preflight unlocked"));
        }
        if (completedSteps.contains("PREFLIGHT") || finalView) {
            events.add(event(base, 50, "AGENTS", "DONE", "Summarized AGENTS preflight", "No write action executed"));
        }
        if (finalView) {
            events.add(event(base, 60, "PR", "DONE", "Prepared targeted PR preview summary", "No PR created"));
            events.add(event(base, 70, "Jira Task", "DONE", "Prepared Jira test case preview", "No Jira issue created"));
            events.add(event(base, 80, "Brief", "DONE", "Final remediation brief assembled", "Ready for human review"));
        }
        if (!humanEvidence(evidence).isEmpty()) {
            events.add(event(base, 90, "Human", "DONE", "Human Evidence Added", "User-provided context attached to the demo state"));
        }
        return List.copyOf(events);
    }

    private ReplayLabAgentEvent event(
            Instant base,
            int elapsedSeconds,
            String category,
            String status,
            String message,
            String details
    ) {
        return new ReplayLabAgentEvent(
                base.plusSeconds(elapsedSeconds),
                elapsedSeconds,
                category,
                status,
                sanitize(message),
                sanitize(details),
                true
        );
    }

    private boolean fallbackUsed(
            List<ReplayLabEvidenceDetail> demoEvidence,
            List<EvidenceEntity> evidence,
            boolean finalView
    ) {
        return finalView || demoEvidence.stream().anyMatch(item -> !"COLLECTED".equals(item.status())) || evidence.isEmpty();
    }

    private List<String> warnings(List<ReplayLabEvidenceDetail> demoEvidence) {
        if (demoEvidence.stream().allMatch(item -> "COLLECTED".equals(item.status()))) {
            return List.of();
        }
        return List.of("Some evidence uses deterministic demo-safe fallback values until live evidence is collected.");
    }

    private List<String> guardrails() {
        return List.of(
                "No PR auto-merge",
                "No automatic deployment",
                "No Jenkins trigger executed",
                "No Jira issue or task created",
                "No infrastructure provisioning executed",
                "No direct push to target branches",
                "No credential or raw production payload disclosure"
        );
    }

    private List<String> nextActions(List<String> completedSteps, boolean finalView) {
        if (finalView) {
            return List.of(
                    "Review the final remediation brief.",
                    "Approve any external write action explicitly before execution.",
                    "Use preview summaries to guide targeted validation."
            );
        }
        if (!completedSteps.contains("EVIDENCE")) {
            return List.of("Collect Jira, Jenkins, Bitbucket, Loki, Tempo, Source, Rovo and AGENTS evidence.");
        }
        if (!completedSteps.contains("RCA")) {
            return List.of("Generate hypothesis-level RCA with confidence boundaries.");
        }
        if (!completedSteps.contains("ENVIRONMENT")) {
            return List.of("Plan a dry-run replay environment or skip it to continue.");
        }
        return List.of("Run AGENTS preflight and prepare preview-only PR and Jira summaries.");
    }

    private boolean hasDemoEvidence(List<EvidenceEntity> evidence) {
        return evidence.stream().anyMatch(item -> List.of(
                "Jira",
                "Jenkins",
                "Bitbucket",
                "Loki",
                "Tempo",
                "Source Context",
                "Rovo",
                "AGENTS.md"
        ).contains(item.getSource()));
    }

    private boolean hasRca(List<EvidenceEntity> evidence) {
        return evidence.stream().anyMatch(item -> SOURCE_RCA.equals(item.getSource())
                || item.getEvidenceType() == EvidenceType.DETERMINISTIC_ROOT_CAUSE
                || item.getEvidenceType() == EvidenceType.ROVO_RCA
                || item.getEvidenceType() == EvidenceType.ROOT_CAUSE_ANALYSIS
                || item.getEvidenceType() == EvidenceType.AI_ROOT_CAUSE);
    }

    private boolean hasEnvironmentDecision(List<EvidenceEntity> evidence) {
        return hasSource(evidence, SOURCE_ENVIRONMENT_PLAN) || hasSource(evidence, SOURCE_ENVIRONMENT_SKIP);
    }

    private boolean hasAgentsEvidence(List<EvidenceEntity> evidence) {
        return hasSource(evidence, "AGENTS.md");
    }

    private boolean hasTargetedPrPreview(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(item -> item.getEvidenceType() == EvidenceType.PULL_REQUEST)
                .map(this::parseMap)
                .anyMatch(value -> value.containsKey("filePath") || value.containsKey("previewOnly"));
    }

    private boolean hasJiraPreview(List<EvidenceEntity> evidence) {
        return evidence.stream().anyMatch(item -> item.getEvidenceType() == EvidenceType.JIRA_TEST_TASK);
    }

    private boolean hasSource(List<EvidenceEntity> evidence, String source) {
        return evidence.stream().anyMatch(item -> source.equals(item.getSource()));
    }

    private boolean isEvidenceLimited(String source, EvidenceEntity evidence) {
        return List.of("Loki", "Tempo", "Rovo").contains(source)
                || confidence(evidence.getConfidence(), source).equals("EVIDENCE_LIMITED");
    }

    private String confidence(Double value, String source) {
        if (List.of("Loki", "Tempo").contains(source)) {
            return "EVIDENCE_LIMITED";
        }
        if (value == null) {
            return "MEDIUM";
        }
        if (value >= 0.85) {
            return "HIGH";
        }
        if (value >= 0.65) {
            return "MEDIUM";
        }
        if (value >= 0.5) {
            return "LOW";
        }
        return "EVIDENCE_LIMITED";
    }

    private List<EvidenceEntity> humanEvidence(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(item -> SOURCE_HUMAN.equals(item.getSource()))
                .toList();
    }

    private Map<String, Object> parseMap(EvidenceEntity evidence) {
        String text = firstNonBlank(evidence.getContentText(), evidence.getBody());
        if (isBlank(text)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    text,
                    new TypeReference<>() {
                    }
            );
            return sanitizeObject(parsed);
        } catch (Exception exception) {
            return Map.of("summary", sanitize(text));
        }
    }

    private Map<String, Object> toMap(Object value) {
        return sanitizeObject(objectMapper.convertValue(
                value,
                new TypeReference<>() {
                }
        ));
    }

    private Map<String, Object> limited(Map<String, Object> value, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> safe = sanitizeObject(value);
        for (String key : keys) {
            if (safe.containsKey(key)) {
                result.put(key, safe.get(key));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeObject(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) {
            return result;
        }
        value.forEach((key, item) -> {
            String safeKey = sanitize(key);
            if (item instanceof String string) {
                result.put(safeKey, sanitize(string));
            } else if (item instanceof Map<?, ?> map) {
                result.put(safeKey, sanitizeObject((Map<String, Object>) map));
            } else if (item instanceof List<?> list) {
                result.put(safeKey, list.stream()
                        .map(this::sanitizeValue)
                        .toList());
            } else {
                result.put(safeKey, item);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value instanceof String string) {
            return sanitize(string);
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeObject((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = evidenceSanitizer.sanitize(value);
        return SENSITIVE_WORDS.matcher(sanitized).replaceAll("[REDACTED]");
    }

    private String sanitizeUrl(String value) {
        String sanitized = sanitize(value);
        return CREDENTIAL_IN_URL.matcher(sanitized).replaceAll("$1[REDACTED]@");
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(sanitizeObject(payload));
        } catch (Exception exception) {
            return "{}";
        }
    }

    private List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> !isBlank(value))
                .map(this::sanitize)
                .toList();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String string(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        return String.valueOf(value);
    }

    private String humanize(String value) {
        String spaced = value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
        if (spaced.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private record DemoEvidenceSeed(
            EvidenceType type,
            String source,
            String summary,
            Map<String, Object> payload,
            double confidenceValue
    ) {
    }
}
