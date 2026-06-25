package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryCandidateHint;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryOrchestrationRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryOrchestrationResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayInputEntity;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayInputRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeChangeAdvisoryOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(
            CodeChangeAdvisoryOrchestrationService.class
    );
    private static final Set<String> ADVISORY_MODES = Set.of(
            "BACKEND_METHOD",
            "FRONTEND_COMPONENT",
            "TEST_SUGGESTION",
            "RISK_REVIEW"
    );
    private static final List<String> SENSITIVE_MARKERS = List.of(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "access_token",
            "refresh_token",
            "id_token",
            "password",
            "privateKey",
            "apiKey",
            "secret"
    );

    private final ReplayCaseRepository caseRepository;
    private final ReplayInputRepository replayInputRepository;
    private final EvidenceRepository evidenceRepository;
    private final CodeChangeAdvisoryService advisoryService;
    private final CodeChangeCandidateExtractionService candidateExtractionService;
    private final ObjectMapper objectMapper;

    public CodeChangeAdvisoryOrchestrationService(
            ReplayCaseRepository caseRepository,
            ReplayInputRepository replayInputRepository,
            EvidenceRepository evidenceRepository,
            CodeChangeAdvisoryService advisoryService,
            CodeChangeCandidateExtractionService candidateExtractionService,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.replayInputRepository = replayInputRepository;
        this.evidenceRepository = evidenceRepository;
        this.advisoryService = advisoryService;
        this.candidateExtractionService = candidateExtractionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CodeChangeAdvisoryOrchestrationResponse orchestrate(
            UUID caseId,
            boolean useCompanyLlm,
            int maxCandidates,
            int companyLlmTimeoutSeconds,
            int maxSnippetChars,
            boolean dryRun,
            CodeChangeAdvisoryOrchestrationRequest request
    ) {
        return orchestrate(
                caseId,
                useCompanyLlm,
                maxCandidates,
                companyLlmTimeoutSeconds,
                maxSnippetChars,
                dryRun,
                null,
                null,
                request
        );
    }

    @Transactional
    public CodeChangeAdvisoryOrchestrationResponse orchestrate(
            UUID caseId,
            boolean useCompanyLlm,
            int maxCandidates,
            int companyLlmTimeoutSeconds,
            int maxSnippetChars,
            boolean dryRun,
            String modelProfile,
            String modelName,
            CodeChangeAdvisoryOrchestrationRequest request
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        advisoryService.validateModelRouting(modelProfile, modelName);
        CodeChangeAdvisoryOrchestrationRequest safeRequest = request == null
                ? new CodeChangeAdvisoryOrchestrationRequest(
                "", "", "", List.of(), List.of(), true, true)
                : request;
        int candidateLimit = Math.max(1, maxCandidates);
        int snippetLimit = Math.max(1, maxSnippetChars);
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();
        Optional<ReplayInputEntity> replayInput =
                include(safeRequest.useLatestSanitizedReplayInput())
                        ? replayInputRepository
                        .findFirstByCaseIdOrderByCreatedAtDesc(caseId)
                        : Optional.empty();
        if (include(safeRequest.useLatestSanitizedReplayInput())
                && replayInput.isEmpty()) {
            missingEvidence.add("SANITIZED_REPLAY_INPUT_MISSING");
        }

        List<CodeChangeAdvisoryCandidateHint> explicitHints =
                safeRequest.candidateHints();
        List<Candidate> candidates = explicitCandidates(safeRequest);
        int requestedCandidateCount = candidates.size();
        if (!explicitHints.isEmpty()) {
            List<CodeChangeAdvisoryCandidateHint> blankSnippetHints =
                    explicitHints.stream()
                            .filter(hint -> !hasText(hint.codeSnippet()))
                            .limit(candidateLimit)
                            .toList();
            if (!blankSnippetHints.isEmpty()) {
                CodeChangeCandidateExtractionService.HydratedCandidateHints
                        hydration = candidateExtractionService
                        .hydrateCandidateHints(
                                caseId,
                                blankSnippetHints,
                                candidateLimit,
                                snippetLimit
                        );
                blockers.addAll(hydration.blockers());
                warnings.addAll(hydration.warnings());
                missingEvidence.addAll(hydration.missingEvidence());
                List<Candidate> submittedWithSnippets = explicitHints.stream()
                        .filter(hint -> hasText(hint.codeSnippet()))
                        .map(hint -> candidate(hint, false))
                        .toList();
                List<Candidate> hydrated = hydration.hints()
                        .stream()
                        .map(hint -> candidate(hint, false))
                        .toList();
                candidates = new ArrayList<>();
                candidates.addAll(submittedWithSnippets);
                candidates.addAll(hydrated);
            }
        }
        if (candidates.isEmpty()
                && include(safeRequest.useLatestSourceAnalysisIfAvailable())) {
            candidates = extractedCandidates(
                    caseId,
                    candidateLimit,
                    snippetLimit
            );
            requestedCandidateCount = candidates.size();
            if (candidates.isEmpty()) {
                warnings.add("SOURCE_ANALYSIS_CANDIDATES_NOT_FOUND");
            }
        }
        if (candidates.isEmpty()) {
            blockers.add("CODE_CHANGE_ADVISORY_CANDIDATES_MISSING");
            missingEvidence.add("CANDIDATE_HINTS_OR_SOURCE_ANALYSIS_REQUIRED");
            nextActions.add("Submit at least one bounded method or component candidate hint");
            return response(
                    replayCase,
                    "NEEDS_CANDIDATES",
                    dryRun,
                    requestedCandidateCount,
                    0,
                    0,
                    0,
                    blockers,
                    warnings,
                    missingEvidence,
                    nextActions,
                    List.of()
            );
        }

        List<String> modes = normalizedModes(safeRequest.advisoryModes());
        List<CodeChangeAdvisoryResultSummary> results = new ArrayList<>();
        int processedCandidates = 0;
        int skippedCandidates = 0;
        for (Candidate candidate : candidates.stream()
                .limit(candidateLimit)
                .toList()) {
            List<String> candidateIssues = candidateIssues(
                    candidate,
                    snippetLimit
            );
            if (!candidateIssues.isEmpty()) {
                skippedCandidates++;
                warnings.addAll(candidateIssues);
                continue;
            }
            List<String> compatibleModes = modes.stream()
                    .filter(mode -> compatible(mode, candidate.language()))
                    .toList();
            if (compatibleModes.isEmpty()) {
                skippedCandidates++;
                warnings.add("ADVISORY_MODE_INCOMPATIBLE:"
                        + candidate.filePath());
                continue;
            }
            processedCandidates++;
            if (candidate.codeSnippet().isBlank()) {
                missingEvidence.add("METHOD_SNIPPET_MISSING:"
                        + candidate.filePath());
            }
            for (String mode : compatibleModes) {
                advisoryService.advise(
                        replayCase.getId(),
                        mode,
                        useCompanyLlm,
                        companyLlmTimeoutSeconds,
                        snippetLimit,
                        modelProfile,
                        modelName,
                        advisoryRequest(
                                safeRequest,
                                candidate,
                                replayInput
                        )
                );
                CodeChangeAdvisoryResultSummary latest =
                        advisoryService.latestResult(replayCase.getId(), mode);
                if (latest != null) {
                    results.add(latest);
                }
            }
        }
        if (processedCandidates == 0) {
            blockers.add("NO_VALID_CODE_CHANGE_ADVISORY_CANDIDATES");
            nextActions.add("Submit a safe bounded candidate with file path, language and snippet or method evidence");
        } else if (results.isEmpty()) {
            blockers.add("NO_ADVISORY_RESULTS_GENERATED");
            nextActions.add("Review candidate validation warnings and retry advisory orchestration");
        } else {
            nextActions.add("Review advisory evaluation summary before any patch planning");
        }

        CodeChangeAdvisoryEvaluationSummaryResponse evaluation =
                advisoryService.summary(replayCase.getId());
        String status = orchestrationStatus(
                blockers,
                warnings,
                results,
                skippedCandidates
        );
        log.info(
                "CODE_CHANGE_ADVISORY_ORCHESTRATION_READY caseId={} jiraKey={} targetKey={} status={} results={}",
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                status,
                results.size()
        );
        return new CodeChangeAdvisoryOrchestrationResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                status,
                dryRun,
                requestedCandidateCount,
                processedCandidates,
                results.size(),
                skippedCandidates,
                distinct(blockers),
                distinct(warnings),
                distinct(missingEvidence),
                distinct(nextActions),
                results,
                evaluation,
                Instant.now()
        );
    }

    private CodeChangeAdvisoryOrchestrationResponse response(
            ReplayCaseEntity replayCase,
            String status,
            boolean dryRun,
            int requestedCandidateCount,
            int processedCandidateCount,
            int advisoryResultCount,
            int skippedCandidateCount,
            List<String> blockers,
            List<String> warnings,
            List<String> missingEvidence,
            List<String> nextActions,
            List<CodeChangeAdvisoryResultSummary> results
    ) {
        return new CodeChangeAdvisoryOrchestrationResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                status,
                dryRun,
                requestedCandidateCount,
                processedCandidateCount,
                advisoryResultCount,
                skippedCandidateCount,
                distinct(blockers),
                distinct(warnings),
                distinct(missingEvidence),
                distinct(nextActions),
                results,
                advisoryService.summary(replayCase.getId()),
                Instant.now()
        );
    }

    private CodeChangeAdvisoryRequest advisoryRequest(
            CodeChangeAdvisoryOrchestrationRequest request,
            Candidate candidate,
            Optional<ReplayInputEntity> replayInput
    ) {
        List<String> constraints = new ArrayList<>(candidate.constraints());
        if (candidate.hydratedFromSource()) {
            constraints.add("hydratedFromSource=true");
        }
        if (hasText(candidate.sourceCandidateSource())) {
            constraints.add("sourceCandidateSource="
                    + candidate.sourceCandidateSource());
        }
        if (hasText(candidate.repositoryLogicalName())) {
            constraints.add("repositoryLogicalName="
                    + candidate.repositoryLogicalName());
        }
        if (hasText(candidate.normalizedFilePath())) {
            constraints.add("normalizedFilePath="
                    + candidate.normalizedFilePath());
        }
        if (candidate.snippetChars() > 0) {
            constraints.add("snippetChars=" + candidate.snippetChars());
        }
        candidate.hydrationWarnings().forEach(warning -> constraints.add(
                "hydrationWarning=" + warning));
        candidate.hydrationMissingEvidence().forEach(missing -> constraints.add(
                "hydrationMissingEvidence=" + missing));
        replayInput.ifPresent(input -> constraints.add(
                "sanitizedReplayInputMetadata traceIdPresent="
                        + hasText(input.getTraceId())
                        + " orderIdPresent="
                        + hasText(input.getOrderId())
                        + " businessKeyPresent="
                        + hasText(input.getBusinessKey())
                        + " endpointPathPresent="
                        + hasText(input.getEndpointPath())
        ));
        return new CodeChangeAdvisoryRequest(
                request.problemSummary(),
                request.expectedBehavior(),
                request.actualBehavior(),
                candidate.filePath(),
                candidate.classOrComponentName(),
                candidate.methodName(),
                candidate.language(),
                candidate.codeSnippet(),
                candidate.relatedDtoSnippet(),
                candidate.relatedLogSummary(),
                constraints
        );
    }

    private List<Candidate> explicitCandidates(
            CodeChangeAdvisoryOrchestrationRequest request
    ) {
        return request.candidateHints()
                .stream()
                .map(hint -> candidate(hint, false))
                .toList();
    }

    private List<Candidate> extractedCandidates(
            UUID caseId,
            int maxCandidates,
            int maxSnippetChars
    ) {
        return candidateExtractionService.extractCandidateHints(
                        caseId,
                        maxCandidates,
                        maxSnippetChars
                )
                .stream()
                .map(hint -> candidate(hint, true))
                .toList();
    }

    private Candidate candidate(
            CodeChangeAdvisoryCandidateHint hint,
            boolean derived
    ) {
        return new Candidate(
                safe(hint.repositoryLogicalName()),
                safe(hint.filePath()),
                safe(hint.classOrComponentName()),
                safe(hint.methodName()),
                normalizeLanguage(hint.language()),
                safe(hint.codeSnippet()),
                safe(hint.relatedDtoSnippet()),
                safe(hint.relatedLogSummary()),
                hint.constraints(),
                booleanConstraint(hint.constraints(), "hydratedFromSource"),
                constraintValue(hint.constraints(), "sourceCandidateSource"),
                constraintValue(hint.constraints(), "normalizedFilePath"),
                intConstraint(hint.constraints(), "snippetChars"),
                constraintValues(hint.constraints(), "hydrationWarning"),
                constraintValues(hint.constraints(), "hydrationMissingEvidence"),
                derived
        );
    }

    private List<Candidate> derivedCandidates(
            UUID caseId,
            int maxCandidates,
            List<String> warnings
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (EvidenceType type : List.of(
                EvidenceType.SOURCE_CONTEXT,
                EvidenceType.AI_ROOT_CAUSE,
                EvidenceType.DETERMINISTIC_ROOT_CAUSE
        )) {
            latestEvidence(caseId, type)
                    .flatMap(this::readJson)
                    .ifPresent(json -> collectCandidates(
                            json,
                            candidates,
                            maxCandidates
                    ));
            if (candidates.size() >= maxCandidates) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            warnings.add("SOURCE_ANALYSIS_CANDIDATES_NOT_FOUND");
        }
        return candidates.stream().limit(maxCandidates).toList();
    }

    private void collectCandidates(
            JsonNode node,
            List<Candidate> candidates,
            int maxCandidates
    ) {
        if (node == null || candidates.size() >= maxCandidates) {
            return;
        }
        if (node.isObject()) {
            String filePath = firstText(node, "filePath", "file", "path");
            if (hasText(filePath)) {
                candidates.add(new Candidate(
                        "",
                        safe(filePath),
                        safe(firstText(
                                node,
                                "classOrComponentName",
                                "className",
                                "componentName"
                        )),
                        safe(firstText(node, "methodName", "method")),
                        normalizeLanguage(firstText(node, "language")),
                        safe(firstText(
                                node,
                                "codeSnippet",
                                "sourceSnippet",
                                "snippet"
                        )),
                        "",
                        "",
                        List.of("derived from persisted source analysis"),
                        false,
                        "",
                        "",
                        0,
                        List.of(),
                        List.of(),
                        true
                ));
                if (candidates.size() >= maxCandidates) {
                    return;
                }
            }
            node.fields().forEachRemaining(entry -> collectCandidates(
                    entry.getValue(),
                    candidates,
                    maxCandidates
            ));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectCandidates(
                    child,
                    candidates,
                    maxCandidates
            ));
        }
    }

    private List<String> candidateIssues(
            Candidate candidate,
            int maxSnippetChars
    ) {
        List<String> issues = new ArrayList<>();
        if (!hasText(candidate.filePath())) {
            issues.add("CANDIDATE_FILE_PATH_MISSING");
        }
        if (!List.of("JAVA", "TYPESCRIPT", "UNKNOWN")
                .contains(candidate.language())) {
            issues.add("CANDIDATE_LANGUAGE_INVALID:"
                    + candidate.filePath());
        }
        if (candidate.codeSnippet().length() > maxSnippetChars) {
            issues.add("CANDIDATE_CODE_SNIPPET_TOO_LARGE:"
                    + candidate.filePath());
        }
        if (candidate.relatedDtoSnippet().length() > maxSnippetChars) {
            issues.add("CANDIDATE_DTO_SNIPPET_TOO_LARGE:"
                    + candidate.filePath());
        }
        String scanned = String.join(
                "\n",
                candidate.repositoryLogicalName(),
                candidate.filePath(),
                candidate.classOrComponentName(),
                candidate.methodName(),
                candidate.codeSnippet(),
                candidate.relatedDtoSnippet(),
                candidate.relatedLogSummary(),
                String.join("\n", candidate.constraints())
        ).toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_MARKERS) {
            if (scanned.contains(marker.toLowerCase(Locale.ROOT))) {
                issues.add("CANDIDATE_SENSITIVE_MARKER_REJECTED:"
                        + marker);
            }
        }
        return distinct(issues);
    }

    private boolean compatible(String mode, String language) {
        if ("BACKEND_METHOD".equals(mode)) {
            return !"TYPESCRIPT".equals(language);
        }
        if ("FRONTEND_COMPONENT".equals(mode)) {
            return !"JAVA".equals(language);
        }
        return true;
    }

    private List<String> normalizedModes(List<String> modes) {
        List<String> normalized = new ArrayList<>();
        if (modes == null || modes.isEmpty()) {
            normalized.add("BACKEND_METHOD");
            return normalized;
        }
        for (String mode : modes) {
            String value = safe(mode).toUpperCase(Locale.ROOT);
            if (ADVISORY_MODES.contains(value)) {
                normalized.add(value);
            }
        }
        return normalized.isEmpty() ? List.of("BACKEND_METHOD") : distinct(normalized);
    }

    private String orchestrationStatus(
            List<String> blockers,
            List<String> warnings,
            List<CodeChangeAdvisoryResultSummary> results,
            int skippedCandidates
    ) {
        if (!blockers.isEmpty()) {
            return "FAILED";
        }
        if (results.isEmpty()) {
            return "NEEDS_CANDIDATES";
        }
        boolean fallback = results.stream()
                .allMatch(result -> !result.llmUsed());
        if (fallback) {
            return "FALLBACK";
        }
        if (skippedCandidates > 0 || !warnings.isEmpty()) {
            return "PARTIAL";
        }
        return "COMPLETED";
    }

    private Optional<EvidenceEntity> latestEvidence(
            UUID caseId,
            EvidenceType type
    ) {
        return evidenceRepository.findByCaseIdAndEvidenceType(caseId, type)
                .stream()
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private Optional<JsonNode> readJson(EvidenceEntity evidence) {
        String content = evidence.getContentText() == null
                ? evidence.getBody()
                : evidence.getContentText();
        if (!hasText(content)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && hasText(value.asText())) {
                return value.asText();
            }
        }
        return "";
    }

    private boolean include(Boolean value) {
        return value == null || value;
    }

    private String normalizeLanguage(String value) {
        String language = safe(value).toUpperCase(Locale.ROOT);
        return switch (language) {
            case "JAVA", "TYPESCRIPT" -> language;
            default -> "UNKNOWN";
        };
    }

    private String constraintValue(List<String> constraints, String key) {
        String prefix = key + "=";
        return constraints == null
                ? ""
                : constraints.stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst()
                .orElse("");
    }

    private List<String> constraintValues(List<String> constraints, String key) {
        String prefix = key + "=";
        return constraints == null
                ? List.of()
                : constraints.stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .toList();
    }

    private boolean booleanConstraint(List<String> constraints, String key) {
        return Boolean.parseBoolean(constraintValue(constraints, key));
    }

    private int intConstraint(List<String> constraints, String key) {
        try {
            return Integer.parseInt(constraintValue(constraints, key));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> distinct(List<String> values) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .filter(this::hasText)
                    .forEach(distinct::add);
        }
        return List.copyOf(distinct);
    }

    private record Candidate(
            String repositoryLogicalName,
            String filePath,
            String classOrComponentName,
            String methodName,
            String language,
            String codeSnippet,
            String relatedDtoSnippet,
            String relatedLogSummary,
            List<String> constraints,
            boolean hydratedFromSource,
            String sourceCandidateSource,
            String normalizedFilePath,
            int snippetChars,
            List<String> hydrationWarnings,
            List<String> hydrationMissingEvidence,
            boolean derived
    ) {
        private Candidate {
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
            hydrationWarnings = hydrationWarnings == null
                    ? List.of()
                    : List.copyOf(hydrationWarnings);
            hydrationMissingEvidence = hydrationMissingEvidence == null
                    ? List.of()
                    : List.copyOf(hydrationMissingEvidence);
        }
    }
}
