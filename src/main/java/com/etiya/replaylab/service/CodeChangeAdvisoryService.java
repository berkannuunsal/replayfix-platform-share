package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryRequest;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResponse;
import com.etiya.replaylab.api.dto.RecommendedCodeChange;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.AiProviderType;
import com.etiya.replaylab.domain.CodeChangeAdvisoryEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import com.etiya.replaylab.repository.CodeChangeAdvisoryRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.service.ai.AiProviderClient;
import com.etiya.replaylab.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeChangeAdvisoryService {

    public static final String REQUEST_TYPE = "CODE_CHANGE_ADVISORY";

    private static final Logger log = LoggerFactory.getLogger(
            CodeChangeAdvisoryService.class
    );
    private static final Set<String> ADVISORY_MODES = Set.of(
            "BACKEND_METHOD",
            "FRONTEND_COMPONENT",
            "TEST_SUGGESTION",
            "RISK_REVIEW"
    );
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "Authorization",
            "access_token",
            "refresh_token",
            "id_token",
            "password",
            "privateKey",
            "apiKey",
            "secret"
    );

    private final ReplayCaseRepository caseRepository;
    private final CodeChangeAdvisoryRepository advisoryRepository;
    private final ReplayLabProperties properties;
    private final AiProviderClientFactory aiProviderClientFactory;
    private final ObjectMapper objectMapper;

    public CodeChangeAdvisoryService(
            ReplayCaseRepository caseRepository,
            CodeChangeAdvisoryRepository advisoryRepository,
            ReplayLabProperties properties,
            AiProviderClientFactory aiProviderClientFactory,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.advisoryRepository = advisoryRepository;
        this.properties = properties;
        this.aiProviderClientFactory = aiProviderClientFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CodeChangeAdvisoryResponse advise(
            UUID caseId,
            String advisoryMode,
            boolean useCompanyLlm,
            int companyLlmTimeoutSeconds,
            int maxSnippetChars,
            CodeChangeAdvisoryRequest request
    ) {
        return advise(
                caseId,
                advisoryMode,
                useCompanyLlm,
                companyLlmTimeoutSeconds,
                maxSnippetChars,
                null,
                null,
                request
        );
    }

    @Transactional
    public CodeChangeAdvisoryResponse advise(
            UUID caseId,
            String advisoryMode,
            boolean useCompanyLlm,
            int companyLlmTimeoutSeconds,
            int maxSnippetChars,
            String modelProfile,
            String modelName,
            CodeChangeAdvisoryRequest request
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        String mode = normalizeMode(advisoryMode);
        validateModelRouting(modelProfile, modelName);
        int snippetLimit = Math.max(1, maxSnippetChars);
        int timeoutSeconds = Math.max(1, companyLlmTimeoutSeconds);
        CodeChangeAdvisoryRequest safeRequest = request == null
                ? new CodeChangeAdvisoryRequest(
                "", "", "", "", "", "", "UNKNOWN",
                "", "", "", List.of())
                : request;
        validate(safeRequest, snippetLimit);

        Map<String, Object> promptSummary = safePromptSummary(
                replayCase,
                mode,
                safeRequest,
                snippetLimit,
                timeoutSeconds
        );
        CodeChangeAdvisoryResponse fallback = fallback(
                replayCase,
                mode,
                "FALLBACK_NOT_REQUESTED",
                "NOT_REQUESTED",
                promptSummary,
                safeRequest
        );
        if (!useCompanyLlm) {
            return persist(replayCase, fallback);
        }
        if (!companyLlmConfigured()) {
            return persist(replayCase, fallback(
                    replayCase,
                    mode,
                    "COMPANY_LLM_UNAVAILABLE",
                    "UNAVAILABLE",
                    promptSummary,
                    safeRequest
            ));
        }

        AiGenerationResponse generation = null;
        try {
            Map<String, Object> context = promptContext(
                    replayCase,
                    mode,
                    safeRequest,
                    snippetLimit
            );
            String promptJson = objectMapper.writeValueAsString(context);
            AiProviderClient provider = aiProviderClientFactory.getProvider();
            generation = provider.generate(
                    new AiGenerationRequest(
                            replayCase.getId(),
                            REQUEST_TYPE,
                            systemPrompt(),
                            userPrompt(mode, promptJson),
                            model(),
                            properties.getAi().getTemperature(),
                            Math.max(1, properties.getAi().getCompany()
                                    .getMaxOutputChars()),
                            true,
                            Map.of(
                                    "requestType",
                                    REQUEST_TYPE,
                                    "advisoryMode",
                                    mode,
                                    "modelProfile",
                                    firstNonBlank(modelProfile, ""),
                                    "modelName",
                                    firstNonBlank(modelName, ""),
                                    "companyLlmTimeoutSeconds",
                                    String.valueOf(timeoutSeconds)
                            )
                    )
            );
            if (!generation.success()) {
                String status = llmStatus(generation.errorCategory());
                return persist(replayCase, fallback(
                        replayCase,
                        mode,
                        fallbackReason(generation.errorCategory()),
                        status,
                        withGenerationMetadata(promptSummary, generation),
                        safeRequest
                ));
            }
            CodeChangeAdvisoryResponse response = parse(
                    replayCase,
                    mode,
                    generation.structuredResponse(),
                    withGenerationMetadata(promptSummary, generation)
            );
            log.info(
                    "CODE_CHANGE_ADVISORY_READY caseId={} jiraKey={} targetKey={} mode={} llmStatus=SUCCESS",
                    replayCase.getId(),
                    replayCase.getJiraKey(),
                    replayCase.getTargetKey(),
                    mode
            );
            return persist(replayCase, response);
        } catch (InvalidCodeChangeAdvisoryException exception) {
            Map<String, Object> fallbackSummary = generation == null
                    ? promptSummary
                    : withGenerationMetadata(promptSummary, generation);
            return persist(replayCase, fallback(
                    replayCase,
                    mode,
                    "INVALID_JSON",
                    "INVALID_JSON",
                    fallbackSummary,
                    safeRequest
            ));
        } catch (Exception exception) {
            String status = timeoutLike(exception) ? "TIMEOUT" : "UNAVAILABLE";
            Map<String, Object> fallbackSummary = generation == null
                    ? promptSummary
                    : withGenerationMetadata(promptSummary, generation);
            return persist(replayCase, fallback(
                    replayCase,
                    mode,
                    status,
                    status,
                    fallbackSummary,
                    safeRequest
            ));
        }
    }

    @Transactional(readOnly = true)
    public CodeChangeAdvisoryEvaluationSummaryResponse summary(UUID caseId) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        List<CodeChangeAdvisoryEntity> advisories =
                advisoryRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
        CodeChangeAdvisoryResultSummary backend =
                latest(advisories, "BACKEND_METHOD");
        CodeChangeAdvisoryResultSummary frontend =
                latest(advisories, "FRONTEND_COMPONENT");
        CodeChangeAdvisoryResultSummary test =
                latest(advisories, "TEST_SUGGESTION");
        CodeChangeAdvisoryResultSummary risk =
                latest(advisories, "RISK_REVIEW");
        List<CodeChangeAdvisoryResultSummary> summaries =
                advisories.stream().map(this::toSummary).toList();
        int actionable = (int) summaries.stream()
                .filter(this::actionable)
                .count();
        int missingEvidence = summaries.stream()
                .mapToInt(value -> value.missingEvidence().size())
                .sum();
        int shouldProceed = (int) summaries.stream()
                .filter(CodeChangeAdvisoryResultSummary::shouldProceedToPatch)
                .count();
        double averageConfidence = summaries.isEmpty()
                ? 0.0
                : summaries.stream()
                .mapToDouble(value -> confidenceScore(value.confidence()))
                .average()
                .orElse(0.0);
        return new CodeChangeAdvisoryEvaluationSummaryResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                advisories.size(),
                backend,
                frontend,
                test,
                risk,
                round(averageConfidence),
                actionable,
                missingEvidence,
                shouldProceed,
                caseAdvisoryStatus(
                        advisories.size(),
                        actionable,
                        missingEvidence,
                        shouldProceed
                ),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public CodeChangeAdvisoryResultSummary latestResult(
            UUID caseId,
            String advisoryMode
    ) {
        return advisoryRepository
                .findFirstByCaseIdAndAdvisoryModeOrderByCreatedAtDesc(
                        caseId,
                        normalizeMode(advisoryMode)
                )
                .map(this::toSummary)
                .orElse(null);
    }

    private CodeChangeAdvisoryResponse persist(
            ReplayCaseEntity replayCase,
            CodeChangeAdvisoryResponse response
    ) {
        CodeChangeAdvisoryEntity entity = new CodeChangeAdvisoryEntity();
        entity.setCaseId(response.caseId());
        entity.setJiraKey(replayCase.getJiraKey());
        entity.setTargetKey(replayCase.getTargetKey());
        entity.setAdvisoryMode(response.advisoryMode());
        entity.setLlmUsed(response.llmUsed());
        entity.setLlmStatus(firstNonBlank(response.llmStatus(), "UNKNOWN"));
        entity.setStatus(firstNonBlank(response.status(), "HYPOTHESIS"));
        entity.setConfidence(response.confidence());
        RecommendedCodeChange change = response.recommendedCodeChange();
        if (change != null) {
            entity.setRecommendedFile(change.file());
            entity.setRecommendedMethodName(change.methodName());
            entity.setRecommendedChangeType(change.changeType());
            entity.setRecommendedDescription(change.description());
            entity.setRecommendedPseudoPatch(change.pseudoPatch());
        }
        entity.setRisksJson(toJson(response.risks()));
        entity.setMissingEvidenceJson(toJson(response.missingEvidence()));
        entity.setTestSuggestionsJson(toJson(response.testSuggestions()));
        entity.setShouldProceedToPatch(response.shouldProceedToPatch());
        entity.setDeterministicFallbackReason(
                response.deterministicFallbackReason()
        );
        entity.setSafePromptSummaryJson(toJson(response.safePromptSummary()));
        entity.setResponseSnapshotJson(toJson(response));
        CodeChangeAdvisoryEntity saved = advisoryRepository.save(entity);
        log.info(
                "CODE_CHANGE_ADVISORY_PERSISTED advisoryId={} caseId={} jiraKey={} targetKey={} mode={} llmStatus={}",
                saved.getId(),
                saved.getCaseId(),
                saved.getJiraKey(),
                saved.getTargetKey(),
                saved.getAdvisoryMode(),
                saved.getLlmStatus()
        );
        return response;
    }

    private CodeChangeAdvisoryResultSummary latest(
            List<CodeChangeAdvisoryEntity> advisories,
            String mode
    ) {
        return advisories.stream()
                .filter(entity -> mode.equals(entity.getAdvisoryMode()))
                .findFirst()
                .map(this::toSummary)
                .orElse(null);
    }

    private CodeChangeAdvisoryResultSummary toSummary(
            CodeChangeAdvisoryEntity entity
    ) {
        return new CodeChangeAdvisoryResultSummary(
                entity.getId(),
                entity.getAdvisoryMode(),
                entity.isLlmUsed(),
                entity.getLlmStatus(),
                entity.getStatus(),
                entity.getConfidence(),
                new RecommendedCodeChange(
                        entity.getRecommendedFile(),
                        entity.getRecommendedMethodName(),
                        entity.getRecommendedChangeType(),
                        entity.getRecommendedDescription(),
                        entity.getRecommendedPseudoPatch()
                ),
                stringsFromJson(entity.getRisksJson()),
                stringsFromJson(entity.getMissingEvidenceJson()),
                stringsFromJson(entity.getTestSuggestionsJson()),
                entity.isShouldProceedToPatch(),
                entity.getDeterministicFallbackReason(),
                mapFromJson(entity.getSafePromptSummaryJson()),
                entity.getCreatedAt()
        );
    }

    private boolean actionable(CodeChangeAdvisoryResultSummary summary) {
        if (summary == null || summary.recommendedCodeChange() == null) {
            return false;
        }
        RecommendedCodeChange change = summary.recommendedCodeChange();
        return !isBlank(change.description())
                && !isBlank(change.changeType())
                && !"ADVISORY_ONLY".equalsIgnoreCase(change.changeType());
    }

    private String caseAdvisoryStatus(
            int advisoryCount,
            int actionableRecommendationCount,
            int missingEvidenceCount,
            int shouldProceedToPatchCount
    ) {
        if (advisoryCount == 0) {
            return "NEEDS_MORE_CONTEXT";
        }
        if (shouldProceedToPatchCount > 0
                && actionableRecommendationCount > 0
                && missingEvidenceCount == 0) {
            return "PATCH_PLAN_CANDIDATE";
        }
        if (actionableRecommendationCount > 0
                && missingEvidenceCount <= actionableRecommendationCount) {
            return "ADVISORY_READY";
        }
        if (actionableRecommendationCount == 0) {
            return "PATCH_NOT_RECOMMENDED";
        }
        return "NEEDS_MORE_CONTEXT";
    }

    private double confidenceScore(String confidence) {
        String value = firstNonBlank(confidence, "")
                .trim()
                .toUpperCase(Locale.ROOT);
        return switch (value) {
            case "LOW" -> 0.33;
            case "MEDIUM" -> 0.66;
            case "HIGH" -> 0.9;
            default -> {
                try {
                    yield Math.max(0.0, Math.min(1.0, Double.parseDouble(value)));
                } catch (NumberFormatException ignored) {
                    yield 0.0;
                }
            }
        };
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Code change advisory could not be serialized"
            );
        }
    }

    private List<String> stringsFromJson(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    value,
                    new TypeReference<List<String>>() {}
            );
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> mapFromJson(String value) {
        if (isBlank(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    value,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void validate(
            CodeChangeAdvisoryRequest request,
            int maxSnippetChars
    ) {
        List<String> errors = new ArrayList<>();
        int codeChars = length(request.codeSnippet());
        int dtoChars = length(request.relatedDtoSnippet());
        if (codeChars > maxSnippetChars) {
            errors.add("codeSnippet exceeds maxSnippetChars");
        }
        if (dtoChars > maxSnippetChars) {
            errors.add("relatedDtoSnippet exceeds maxSnippetChars");
        }
        List<String> scanned = new ArrayList<>();
        scanned.add(request.codeSnippet());
        scanned.add(request.relatedDtoSnippet());
        scanned.add(request.relatedLogSummary());
        scanned.add(request.problemSummary());
        scanned.add(request.expectedBehavior());
        scanned.add(request.actualBehavior());
        scanned.addAll(request.constraints());
        for (String value : scanned) {
            String lower = firstNonBlank(value, "")
                    .toLowerCase(Locale.ROOT);
            for (String marker : FORBIDDEN_MARKERS) {
                if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                    errors.add("Forbidden sensitive marker found: " + marker);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.join("; ", distinct(errors))
            );
        }
    }

    private CodeChangeAdvisoryResponse parse(
            ReplayCaseEntity replayCase,
            String mode,
            JsonNode node,
            Map<String, Object> promptSummary
    ) {
        if (node == null || !node.isObject()) {
            throw new InvalidCodeChangeAdvisoryException();
        }
        JsonNode change = node.path("recommendedCodeChange");
        if (!change.isObject()) {
            throw new InvalidCodeChangeAdvisoryException();
        }
        return new CodeChangeAdvisoryResponse(
                replayCase.getId(),
                mode,
                true,
                "SUCCESS",
                normalizeStatus(node.path("status").asText("HYPOTHESIS")),
                safeText(node.path("confidence").asText("")),
                new RecommendedCodeChange(
                        safeText(change.path("file").asText("")),
                        safeText(change.path("methodName").asText("")),
                        safeText(change.path("changeType").asText("")),
                        safeText(change.path("description").asText("")),
                        safeText(change.path("pseudoPatch").asText(""))
                ),
                safeStrings(node.path("risks")),
                safeStrings(node.path("missingEvidence")),
                safeStrings(node.path("testSuggestions")),
                node.path("shouldProceedToPatch").asBoolean(false),
                "",
                promptSummary,
                Instant.now()
        );
    }

    private CodeChangeAdvisoryResponse fallback(
            ReplayCaseEntity replayCase,
            String mode,
            String fallbackReason,
            String llmStatus,
            Map<String, Object> promptSummary,
            CodeChangeAdvisoryRequest request
    ) {
        String file = safeText(request.filePath());
        String method = safeText(request.methodName());
        List<String> missingEvidence = new ArrayList<>();
        if (isBlank(request.codeSnippet())) {
            missingEvidence.add("CODE_SNIPPET_MISSING");
        }
        if (isBlank(request.problemSummary())) {
            missingEvidence.add("PROBLEM_SUMMARY_MISSING");
        }
        return new CodeChangeAdvisoryResponse(
                replayCase.getId(),
                mode,
                false,
                firstNonBlank(llmStatus, "FALLBACK"),
                "HYPOTHESIS",
                "0.0",
                new RecommendedCodeChange(
                        file,
                        method,
                        "ADVISORY_ONLY",
                        "Review the supplied method-level context and resolve missing evidence before generating a patch.",
                        ""
                ),
                List.of(
                        "No code change has been validated by replay execution.",
                        "Do not proceed to patch without human review and focused tests."
                ),
                distinct(missingEvidence),
                List.of(
                        "Add or update a focused regression test around the supplied method/component.",
                        "Verify behavior with sanitized replay input before patch generation."
                ),
                false,
                firstNonBlank(fallbackReason, "FALLBACK"),
                promptSummary,
                Instant.now()
        );
    }

    private Map<String, Object> promptContext(
            ReplayCaseEntity replayCase,
            String mode,
            CodeChangeAdvisoryRequest request,
            int maxSnippetChars
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("caseId", replayCase.getId().toString());
        context.put("jiraKey", safeText(replayCase.getJiraKey()));
        context.put("targetKey", safeText(replayCase.getTargetKey()));
        context.put("advisoryMode", mode);
        context.put("problemSummary", safeText(request.problemSummary()));
        context.put("expectedBehavior", safeText(request.expectedBehavior()));
        context.put("actualBehavior", safeText(request.actualBehavior()));
        context.put("filePath", safeText(request.filePath()));
        context.put("classOrComponentName",
                safeText(request.classOrComponentName()));
        context.put("methodName", safeText(request.methodName()));
        context.put("language", normalizeLanguage(request.language()));
        context.put("codeSnippet", truncate(
                safeText(request.codeSnippet()),
                maxSnippetChars
        ));
        context.put("relatedDtoSnippet", truncate(
                safeText(request.relatedDtoSnippet()),
                maxSnippetChars
        ));
        context.put("relatedLogSummary",
                safeText(request.relatedLogSummary()));
        context.put("constraints", safeStrings(request.constraints()));
        context.put("rules", List.of(
                "Advisory only; do not write files.",
                "Use HYPOTHESIS language unless replay execution exists.",
                "Do not suggest production access or secret exposure."
        ));
        return context;
    }

    private Map<String, Object> safePromptSummary(
            ReplayCaseEntity replayCase,
            String mode,
            CodeChangeAdvisoryRequest request,
            int maxSnippetChars,
            int timeoutSeconds
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseId", replayCase.getId().toString());
        summary.put("jiraKey", safeText(replayCase.getJiraKey()));
        summary.put("targetKey", safeText(replayCase.getTargetKey()));
        summary.put("advisoryMode", mode);
        summary.put("filePath", safeText(request.filePath()));
        summary.put("classOrComponentName",
                safeText(request.classOrComponentName()));
        summary.put("methodName", safeText(request.methodName()));
        summary.put("language", normalizeLanguage(request.language()));
        summary.put("codeSnippetChars", length(request.codeSnippet()));
        summary.put("relatedDtoSnippetChars",
                length(request.relatedDtoSnippet()));
        summary.put("relatedLogSummaryChars",
                length(request.relatedLogSummary()));
        summary.put("constraintsCount", request.constraints().size());
        addHydrationSummary(summary, request.constraints());
        summary.put("maxSnippetChars", maxSnippetChars);
        summary.put("companyLlmTimeoutSeconds", timeoutSeconds);
        return summary;
    }

    private void addHydrationSummary(
            Map<String, Object> summary,
            List<String> constraints
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        for (String constraint : constraints) {
            String value = firstNonBlank(constraint, "");
            if (value.equals("hydratedFromSource=true")) {
                summary.put("hydratedFromSource", true);
            } else if (value.startsWith("sourceCandidateSource=")) {
                summary.put(
                        "sourceCandidateSource",
                        value.substring("sourceCandidateSource=".length())
                );
            } else if (value.startsWith("repositoryLogicalName=")) {
                summary.put(
                        "repositoryLogicalName",
                        value.substring("repositoryLogicalName=".length())
                );
            } else if (value.startsWith("normalizedFilePath=")) {
                summary.put(
                        "normalizedFilePath",
                        value.substring("normalizedFilePath=".length())
                );
            } else if (value.startsWith("snippetChars=")) {
                summary.put(
                        "snippetChars",
                        intValue(value.substring("snippetChars=".length()))
                );
            } else if (value.startsWith("hydrationWarning=")) {
                warnings.add(value.substring("hydrationWarning=".length()));
            } else if (value.startsWith("hydrationMissingEvidence=")) {
                missingEvidence.add(value.substring(
                        "hydrationMissingEvidence=".length()));
            }
        }
        if (!warnings.isEmpty()) {
            summary.put("hydrationWarnings", List.copyOf(warnings));
        }
        if (!missingEvidence.isEmpty()) {
            summary.put(
                    "hydrationMissingEvidence",
                    List.copyOf(missingEvidence)
            );
        }
    }

    private int intValue(String value) {
        try {
            return Integer.parseInt(firstNonBlank(value, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String systemPrompt() {
        return """
                You are ReplayLab code change advisory AI. Return one valid
                JSON object only. No markdown. No reasoning. Use HYPOTHESIS
                language. This is advisory only: do not claim a fix is
                confirmed, do not ask to write files, do not suggest commits,
                pull requests, Jenkins, deployments, production data access or
                secret exposure.
                """;
    }

    private String userPrompt(String mode, String contextJson) {
        return """
                Return strict JSON matching this schema:
                {
                  "status": "HYPOTHESIS",
                  "confidence": "LOW|MEDIUM|HIGH or 0.0-1.0",
                  "recommendedCodeChange": {
                    "file": "",
                    "methodName": "",
                    "changeType": "",
                    "description": "",
                    "pseudoPatch": ""
                  },
                  "risks": [],
                  "missingEvidence": [],
                  "testSuggestions": [],
                  "shouldProceedToPatch": false
                }

                Advisory mode: %s
                Sanitized source context:
                %s
                """.formatted(mode, contextJson);
    }

    private String normalizeMode(String value) {
        String mode = firstNonBlank(value, "BACKEND_METHOD")
                .trim()
                .toUpperCase(Locale.ROOT);
        return ADVISORY_MODES.contains(mode) ? mode : "BACKEND_METHOD";
    }

    private String normalizeLanguage(String value) {
        String language = firstNonBlank(value, "UNKNOWN")
                .trim()
                .toUpperCase(Locale.ROOT);
        return switch (language) {
            case "JAVA", "TYPESCRIPT" -> language;
            default -> "UNKNOWN";
        };
    }

    private String normalizeStatus(String value) {
        return "HYPOTHESIS".equalsIgnoreCase(value) ? "HYPOTHESIS" : "HYPOTHESIS";
    }

    private String llmStatus(String errorCategory) {
        String category = firstNonBlank(errorCategory, "UNAVAILABLE")
                .toUpperCase(Locale.ROOT);
        if (category.contains("TIMEOUT")) {
            return "TIMEOUT";
        }
        if (category.contains("INVALID_JSON")) {
            return "INVALID_JSON";
        }
        if (category.contains("DISABLED")) {
            return "UNAVAILABLE";
        }
        return "UNAVAILABLE";
    }

    private String fallbackReason(String errorCategory) {
        String category = firstNonBlank(errorCategory, "UNAVAILABLE")
                .toUpperCase(Locale.ROOT);
        if (category.contains("TIMEOUT")) {
            return "TIMEOUT";
        }
        if (category.contains("INVALID_JSON")) {
            return "INVALID_JSON";
        }
        return category;
    }

    private boolean companyLlmConfigured() {
        return properties.getAi().isEnabled()
                && (properties.getAi().getProvider() == AiProviderType.COMPANY_LLM
                || properties.getAi().getProvider()
                == AiProviderType.LITELLM_OPENAI_COMPATIBLE
                || liteLlmRoutingActive());
    }

    private String model() {
        if (properties.getAi().getProvider()
                == AiProviderType.LITELLM_OPENAI_COMPATIBLE
                || liteLlmRoutingActive("", "")) {
            return firstNonBlank(
                    properties.getLlm().getDefaultModelName(),
                    "openai/gpt-3.5-turbo"
            );
        }
        return firstNonBlank(
                properties.getAi().getCompany().getModel(),
                properties.getAi().getModel(),
                "company-llm"
        );
    }

    public void validateModelRouting(String modelProfile, String modelName) {
        if (!liteLlmRoutingActive(modelProfile, modelName)) {
            return;
        }
        if (modelName == null || modelName.isBlank()) {
            return;
        }
        if (!modelName.startsWith("openai/")) {
            throw new IllegalArgumentException(
                    "MODEL_NAME_MUST_USE_OPENAI_PREFIX"
            );
        }
        List<String> allowed = properties.getLlm().getAllowedModelNames();
        if (allowed != null && !allowed.isEmpty()
                && !allowed.contains(modelName)) {
            throw new IllegalArgumentException("MODEL_NOT_ALLOWED");
        }
    }

    private boolean liteLlmRoutingActive() {
        return liteLlmRoutingActive("", "");
    }

    private boolean liteLlmRoutingActive(String modelProfile, String modelName) {
        return properties.getAi().getProvider()
                == AiProviderType.LITELLM_OPENAI_COMPATIBLE
                || properties.getLlm().getProvider()
                == AiProviderType.LITELLM_OPENAI_COMPATIBLE
                || (!isBlank(modelProfile)
                && !properties.getLlm().isAllowPlainModelNames())
                || (!isBlank(modelName)
                && !properties.getLlm().isAllowPlainModelNames())
                || (!isBlank(properties.getLlm().getBaseUrl())
                && firstNonBlank(properties.getLlm().getDefaultModelName(), "")
                .startsWith("openai/"));
    }

    private Map<String, Object> withGenerationMetadata(
            Map<String, Object> promptSummary,
            AiGenerationResponse generation
    ) {
        Map<String, Object> values = new LinkedHashMap<>(promptSummary);
        values.remove("monthlyBudgetUsd");
        values.put("provider", generation.provider());
        values.put("modelProfile", generation.modelProfile());
        values.put("effectiveModelName", generation.effectiveModelName());
        values.put("effectiveTimeoutSeconds",
                generation.effectiveTimeoutSeconds());
        values.put("effectiveMaxPromptChars",
                generation.effectiveMaxPromptChars());
        values.put("effectiveMaxOutputTokens",
                generation.effectiveMaxOutputTokens());
        values.put("budgetTrackingEnabled",
                generation.budgetTrackingEnabled());
        values.put("budgetPeriod", generation.budgetPeriod());
        values.put("weeklyBudgetUsd", generation.weeklyBudgetUsd());
        values.put("estimatedUsageAvailable",
                generation.estimatedUsageAvailable());
        values.put("promptTokenCount", generation.promptTokenCount());
        values.put("completionTokenCount",
                generation.completionTokenCount());
        values.put("totalTokenCount", generation.totalTokenCount());
        return values;
    }

    private boolean timeoutLike(Exception exception) {
        String text = exception.getClass().getSimpleName()
                + " "
                + firstNonBlank(exception.getMessage(), "");
        return text.toLowerCase(Locale.ROOT).contains("timeout");
    }

    private List<String> safeStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(safeText(item.asText(""))));
        }
        return distinct(values);
    }

    private List<String> safeStrings(List<String> values) {
        List<String> safe = new ArrayList<>();
        if (values != null) {
            values.forEach(value -> safe.add(safeText(value)));
        }
        return distinct(safe);
    }

    private List<String> distinct(List<String> values) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(distinct::add);
        }
        return List.copyOf(distinct);
    }

    private String safeText(String value) {
        String safe = firstNonBlank(value, "")
                .replaceAll("(?is)reasoning_content\\s*[:=].*", "[REDACTED_REASONING_CONTENT]")
                .trim();
        return truncate(safe, 20_000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static class InvalidCodeChangeAdvisoryException
            extends RuntimeException {
    }
}
