package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayEnvironmentDemoSummaryResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentLlmAdvisoryRequest;
import com.etiya.replayfix.api.dto.ReplayEnvironmentLlmAdvisoryResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentRequestResponse;
import com.etiya.replayfix.api.dto.ReplayRuntimeDependencyPlan;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class ReplayEnvironmentLlmAdvisoryService {

    public static final String REQUEST_TYPE = "REPLAY_ENVIRONMENT_ADVISORY";

    private static final Logger log = LoggerFactory.getLogger(
            ReplayEnvironmentLlmAdvisoryService.class
    );
    private static final Set<String> ADVISORY_MODES = Set.of(
            "ARCHITECTURE_REVIEW",
            "RUNTIME_DEPENDENCY_REVIEW",
            "NEXT_CODEX_TASK",
            "GO_NO_GO",
            "CUSTOM"
    );

    private final ReplayEnvironmentRequestService requestService;
    private final ReplayFixProperties properties;
    private final AiProviderClientFactory aiProviderClientFactory;
    private final ObjectMapper objectMapper;

    public ReplayEnvironmentLlmAdvisoryService(
            ReplayEnvironmentRequestService requestService,
            ReplayFixProperties properties,
            AiProviderClientFactory aiProviderClientFactory,
            ObjectMapper objectMapper
    ) {
        this.requestService = requestService;
        this.properties = properties;
        this.aiProviderClientFactory = aiProviderClientFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReplayEnvironmentLlmAdvisoryResponse advise(
            UUID requestId,
            String advisoryMode,
            boolean useCompanyLlm,
            ReplayEnvironmentLlmAdvisoryRequest request
    ) {
        String mode = normalizeMode(advisoryMode);
        ReplayEnvironmentRequestResponse requestResponse =
                requestService.get(requestId);
        ReplayEnvironmentPlanResponse plan = requestService.getPlan(requestId);
        boolean includeProvisionReadiness =
                include(request == null ? null : request.includeProvisionReadiness());
        boolean includeDemoSummary =
                include(request == null ? null : request.includeDemoSummary());
        ReplayEnvironmentProvisionReadinessResponse readiness =
                includeProvisionReadiness
                        ? requestService.provisionReadiness(requestId)
                        : null;
        ReplayEnvironmentDemoSummaryResponse demo =
                includeDemoSummary ? requestService.demoSummary(requestId) : null;

        List<String> blockers = blockers(plan, readiness, demo);
        List<String> warnings = warnings(readiness, demo);
        List<String> nextActions = nextActions(plan, readiness, demo);
        Map<String, Object> fallback = fallbackAdvisory(
                mode,
                requestResponse,
                plan,
                readiness,
                demo,
                blockers,
                nextActions
        );

        if (!useCompanyLlm) {
            return response(
                    requestResponse,
                    mode,
                    false,
                    "NOT_REQUESTED",
                    "FALLBACK",
                    fallback,
                    blockers,
                    warnings,
                    nextActions
            );
        }

        if (!companyLlmConfigured()) {
            return response(
                    requestResponse,
                    mode,
                    false,
                    "UNAVAILABLE",
                    "UNAVAILABLE",
                    fallback,
                    blockers,
                    warningsWith(warnings, "COMPANY_LLM_UNAVAILABLE"),
                    nextActions
            );
        }

        try {
            Map<String, Object> context = safePromptContext(
                    mode,
                    request,
                    plan,
                    readiness,
                    demo
            );
            String prompt = sanitizeText(objectMapper.writeValueAsString(context));
            AiProviderClient provider = aiProviderClientFactory.getProvider();
            AiGenerationResponse generation = provider.generate(
                    new AiGenerationRequest(
                            requestResponse.caseId(),
                            REQUEST_TYPE,
                            systemPrompt(),
                            userPrompt(mode, prompt),
                            model(),
                            properties.getAi().getTemperature(),
                            Math.max(1, properties.getAi().getCompany()
                                    .getMaxOutputChars()),
                            true,
                            Map.of(
                                    "requestType",
                                    REQUEST_TYPE,
                                    "advisoryMode",
                                    mode
                            )
                    )
            );

            if (!generation.success()) {
                String status = advisoryStatusFrom(generation.errorCategory());
                return response(
                        requestResponse,
                        mode,
                        false,
                        firstNonBlank(generation.errorCategory(), "UNAVAILABLE"),
                        status,
                        fallback,
                        blockers,
                        warningsWith(warnings, generation.warnings()),
                        nextActions
                );
            }

            Map<String, Object> advisory = parseAdvisory(
                    generation.structuredResponse()
            );
            log.info(
                    "REPLAY_ENV_LLM_ADVISORY_READY requestId={} caseId={} jiraKey={} targetKey={} mode={} llmStatus=SUCCESS",
                    requestResponse.requestId(),
                    requestResponse.caseId(),
                    requestResponse.jiraKey(),
                    requestResponse.targetKey(),
                    mode
            );
            return response(
                    requestResponse,
                    mode,
                    true,
                    "SUCCESS",
                    "SUCCESS",
                    advisory,
                    blockers,
                    warningsWith(warnings, generation.warnings()),
                    nextActions
            );
        } catch (InvalidAdvisoryJsonException exception) {
            return response(
                    requestResponse,
                    mode,
                    false,
                    "INVALID_JSON",
                    "INVALID_JSON",
                    fallback,
                    blockers,
                    warningsWith(warnings, "COMPANY_LLM_INVALID_JSON"),
                    nextActions
            );
        } catch (Exception exception) {
            String status = timeoutLike(exception) ? "TIMEOUT" : "UNAVAILABLE";
            return response(
                    requestResponse,
                    mode,
                    false,
                    status,
                    status,
                    fallback,
                    blockers,
                    warningsWith(warnings, "COMPANY_LLM_" + status),
                    nextActions
            );
        }
    }

    private Map<String, Object> safePromptContext(
            String mode,
            ReplayEnvironmentLlmAdvisoryRequest request,
            ReplayEnvironmentPlanResponse plan,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            ReplayEnvironmentDemoSummaryResponse demo
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("advisoryMode", mode);
        context.put("question", sanitizeText(
                request == null ? "" : firstNonBlank(request.question(), "")
        ));
        context.put("focusAreas", safeStrings(
                request == null ? List.of() : request.focusAreas()
        ));
        context.put("jiraKey", plan.jiraKey());
        context.put("targetKey", plan.targetKey());
        context.put("requestStatus", demo == null
                ? null
                : demo.requestStatus());
        context.put("planStatus", plan.status());
        context.put("readinessStatus", readiness == null
                ? null
                : readiness.readinessStatus());
        context.put("replayNamespace", plan.namespacePlan() == null
                ? null
                : plan.namespacePlan().proposedReplayNamespace());
        context.put("proposedHost", plan.accessRoutingPlan() == null
                ? null
                : plan.accessRoutingPlan().proposedHost());
        context.put("backendReplayApp", plan.backend() == null
                ? null
                : plan.backend().replayArgoCdApplicationName());
        context.put("customerUiReplayApp", plan.customerUi() == null
                ? null
                : plan.customerUi().replayArgoCdApplicationName());
        context.put("accessMode", plan.accessRoutingPlan() == null
                ? null
                : plan.accessRoutingPlan().mode());
        context.put("dbStrategy", plan.dbStrategyPlan() == null
                ? null
                : plan.dbStrategyPlan().strategy());
        if (include(request == null ? null : request.includeRuntimeDependencies())) {
            context.put("runtimeDependencies", runtimeDependencyContext(plan));
        }
        context.put("guardrails", safeStrings(plan.guardrails()));
        context.put("nextActions", safeStrings(plan.nextActions()));
        if (readiness != null) {
            context.put("readinessBlockers", safeStrings(readiness.blockers()));
            context.put("readinessRequiredActions",
                    safeStrings(readiness.requiredActions()));
        }
        return safeMap(context);
    }

    private List<Map<String, Object>> runtimeDependencyContext(
            ReplayEnvironmentPlanResponse plan
    ) {
        List<Map<String, Object>> values = new ArrayList<>();
        List<ReplayRuntimeDependencyPlan> dependencies =
                plan.runtimeDependencies() == null
                        ? List.of()
                        : plan.runtimeDependencies();
        for (ReplayRuntimeDependencyPlan dependency : dependencies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dependencyName", dependency.dependencyName());
            item.put("dependencyType", dependency.dependencyType());
            item.put("mode", dependency.mode());
            item.put("blockers", safeStrings(dependency.blockers()));
            item.put("warnings", safeStrings(dependency.warnings()));
            values.add(safeMap(item));
        }
        return values;
    }

    private Map<String, Object> parseAdvisory(JsonNode structuredResponse) {
        if (structuredResponse == null || !structuredResponse.isObject()) {
            throw new InvalidAdvisoryJsonException();
        }
        Map<String, Object> parsed = objectMapper.convertValue(
                structuredResponse,
                new TypeReference<Map<String, Object>>() {}
        );
        Map<String, Object> advisory = safeMap(parsed);
        if (!advisory.keySet().containsAll(List.of(
                "overallAssessment",
                "mainRisks",
                "questionsForTeams",
                "recommendedNextSteps",
                "recommendedCodexTasks",
                "goNoGoForRealProvisioning"
        ))) {
            throw new InvalidAdvisoryJsonException();
        }
        return advisory;
    }

    private Map<String, Object> fallbackAdvisory(
            String mode,
            ReplayEnvironmentRequestResponse request,
            ReplayEnvironmentPlanResponse plan,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            ReplayEnvironmentDemoSummaryResponse demo,
            List<String> blockers,
            List<String> nextActions
    ) {
        boolean ready = readiness != null
                && "READY".equals(readiness.readinessStatus())
                && blockers.isEmpty();
        List<Map<String, Object>> risks = new ArrayList<>();
        if (!blockers.isEmpty()) {
            risks.add(Map.of(
                    "risk",
                    "Replay environment is not ready for real ArgoCD provisioning.",
                    "severity",
                    "HIGH",
                    "whyItMatters",
                    "The stored readiness check still has blockers that must be reviewed before provisioning.",
                    "recommendedMitigation",
                    "Resolve blockers and rerun provision readiness.",
                    "ownerTeam",
                    "ReplayFix"
            ));
        }
        if (plan.runtimeDependencies() != null) {
            for (ReplayRuntimeDependencyPlan dependency : plan.runtimeDependencies()) {
                if (!dependency.blockers().isEmpty()) {
                    risks.add(Map.of(
                            "risk",
                            "Runtime dependency blocker for "
                                    + dependency.dependencyName(),
                            "severity",
                            "HIGH",
                            "whyItMatters",
                            String.join(", ", dependency.blockers()),
                            "recommendedMitigation",
                            "Confirm isolated or disabled runtime mode before provisioning.",
                            "ownerTeam",
                            ownerFor(dependency.dependencyType())
                    ));
                }
            }
        }
        if (risks.isEmpty()) {
            risks.add(Map.of(
                    "risk",
                    "Real provisioning remains advisory-only until approval gates are configured.",
                    "severity",
                    "MEDIUM",
                    "whyItMatters",
                    "ReplayFix has not executed ArgoCD sync or replay execution.",
                    "recommendedMitigation",
                    "Keep status as advisory until replay execution reproduces the issue.",
                    "ownerTeam",
                    "ReplayFix"
            ));
        }
        List<Map<String, Object>> recommendedSteps = new ArrayList<>();
        List<String> actions = nextActions.isEmpty()
                ? List.of("Review stored plan, readiness blockers and guardrails.")
                : nextActions;
        int priority = 1;
        for (String action : actions) {
            recommendedSteps.add(Map.of(
                    "priority",
                    priority++,
                    "step",
                    action,
                    "reason",
                    "Required before moving from advisory planning to real provisioning.",
                    "ownerTeam",
                    "ReplayFix"
            ));
        }
        Map<String, Object> advisory = new LinkedHashMap<>();
        advisory.put(
                "overallAssessment",
                overallAssessment(mode, request, plan, readiness, demo)
        );
        advisory.put("mainRisks", risks);
        advisory.put("questionsForTeams", Map.of(
                "infra",
                List.of("Is the proposed replay namespace and host approved for test2 replay?"),
                "security",
                List.of("Is the secret strategy limited to configured key names with no value exposure?"),
                "dba",
                List.of("Is the DB runtime strategy approved for replay execution?"),
                "backend",
                List.of("Are DB and messaging runtime modes safe for the monolith startup path?"),
                "frontend",
                List.of("Is the customer UI backend base URL override correct for the replay route?")
        ));
        advisory.put("recommendedNextSteps", recommendedSteps);
        advisory.put("recommendedCodexTasks", recommendedCodexTasks(mode, blockers));
        advisory.put("goNoGoForRealProvisioning", Map.of(
                "ready",
                ready,
                "blockers",
                blockers,
                "requiredApprovals",
                plan.requiredApprovals() == null
                        ? List.of()
                        : plan.requiredApprovals()
        ));
        return advisory;
    }

    private List<Map<String, Object>> recommendedCodexTasks(
            String mode,
            List<String> blockers
    ) {
        String taskName = "Resolve replay environment readiness blockers";
        String goal = "Make the approved replay environment request ready for real ArgoCD provisioning.";
        String whyNow = "Provision readiness is blocked by stored planning evidence.";
        if ("NEXT_CODEX_TASK".equals(mode)) {
            taskName = blockers.isEmpty()
                    ? "Add pre-provisioning safety checklist enforcement"
                    : "Resolve replay environment provision readiness blockers";
            goal = blockers.isEmpty()
                    ? "Persist a checklist that confirms ArgoCD, namespace, ingress, secret, DB and runtime dependency gates."
                    : "Clear blockers before enabling any real ArgoCD provisioning path.";
            whyNow = blockers.isEmpty()
                    ? "The request is close to provision readiness but still needs an explicit gate."
                    : "The next implementation task should target the blockers already reported by readiness.";
        }
        return List.of(Map.of(
                "taskName",
                taskName,
                "goal",
                goal,
                "whyNow",
                whyNow,
                "mustNotDo",
                List.of(
                        "Do not call ArgoCD sync",
                        "Do not create Kubernetes namespaces",
                        "Do not expose credential values"
                )
        ));
    }

    private String overallAssessment(
            String mode,
            ReplayEnvironmentRequestResponse request,
            ReplayEnvironmentPlanResponse plan,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            ReplayEnvironmentDemoSummaryResponse demo
    ) {
        String readinessStatus = readiness == null
                ? "UNKNOWN"
                : readiness.readinessStatus();
        return "ADVISORY: Replay environment request "
                + request.requestId()
                + " for "
                + plan.jiraKey()
                + " is in request status "
                + request.status()
                + " with plan status "
                + plan.status()
                + " and provision readiness "
                + readinessStatus
                + ". No replay execution has confirmed the issue.";
    }

    private ReplayEnvironmentLlmAdvisoryResponse response(
            ReplayEnvironmentRequestResponse request,
            String mode,
            boolean llmUsed,
            String llmStatus,
            String advisoryStatus,
            Map<String, Object> advisory,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        Map<String, Object> safeAdvisory = safeMap(advisory);
        return new ReplayEnvironmentLlmAdvisoryResponse(
                request.requestId(),
                request.caseId(),
                request.jiraKey(),
                request.targetKey(),
                mode,
                llmUsed,
                firstNonBlank(llmStatus, "UNAVAILABLE"),
                firstNonBlank(advisoryStatus, "FALLBACK"),
                safeAdvisory,
                safeStrings(blockers),
                safeStrings(warnings),
                safeStrings(nextActions),
                preview(safeAdvisory),
                Instant.now()
        );
    }

    private String systemPrompt() {
        return """
                You are advising ReplayFix about a dry-run replay environment.
                Return one valid JSON object only. No markdown. No reasoning.
                Use HYPOTHESIS or ADVISORY language. Never say CONFIRMED
                unless supplied replay execution evidence says the issue reproduced.
                If a fact is unknown, say UNKNOWN. Give practical next steps and
                owner teams. Do not recommend production DB access, production
                write, credential disclosure, deployment, PR creation or ArgoCD
                sync.
                """;
    }

    private String userPrompt(String mode, String contextJson) {
        return """
                Return strict JSON matching this schema:
                {
                  "overallAssessment": "",
                  "mainRisks": [
                    {
                      "risk": "",
                      "severity": "LOW|MEDIUM|HIGH|CRITICAL",
                      "whyItMatters": "",
                      "recommendedMitigation": "",
                      "ownerTeam": "Infra|Security|DBA|Backend|Frontend|ReplayFix|Unknown"
                    }
                  ],
                  "questionsForTeams": {
                    "infra": [],
                    "security": [],
                    "dba": [],
                    "backend": [],
                    "frontend": []
                  },
                  "recommendedNextSteps": [
                    {
                      "priority": 1,
                      "step": "",
                      "reason": "",
                      "ownerTeam": ""
                    }
                  ],
                  "recommendedCodexTasks": [
                    {
                      "taskName": "",
                      "goal": "",
                      "whyNow": "",
                      "mustNotDo": []
                    }
                  ],
                  "goNoGoForRealProvisioning": {
                    "ready": false,
                    "blockers": [],
                    "requiredApprovals": []
                  }
                }

                Advisory mode: %s
                Sanitized replay environment context:
                %s
                """.formatted(mode, contextJson);
    }

    private boolean companyLlmConfigured() {
        return properties.getAi().isEnabled()
                && properties.getAi().getProvider() == AiProviderType.COMPANY_LLM;
    }

    private String model() {
        return firstNonBlank(
                properties.getAi().getCompany().getModel(),
                properties.getAi().getModel(),
                "company-llm"
        );
    }

    private String normalizeMode(String value) {
        String mode = firstNonBlank(value, "ARCHITECTURE_REVIEW")
                .trim()
                .toUpperCase(Locale.ROOT);
        return ADVISORY_MODES.contains(mode) ? mode : "CUSTOM";
    }

    private String advisoryStatusFrom(String errorCategory) {
        String category = firstNonBlank(errorCategory, "UNAVAILABLE")
                .toUpperCase(Locale.ROOT);
        if (category.contains("TIMEOUT")) {
            return "TIMEOUT";
        }
        if (category.contains("INVALID_JSON")) {
            return "INVALID_JSON";
        }
        if (category.contains("DISABLED") || category.contains("UNAVAILABLE")) {
            return "UNAVAILABLE";
        }
        return "FALLBACK";
    }

    private boolean timeoutLike(Exception exception) {
        String text = exception.getClass().getSimpleName()
                + " "
                + firstNonBlank(exception.getMessage(), "");
        return text.toLowerCase(Locale.ROOT).contains("timeout");
    }

    private List<String> blockers(
            ReplayEnvironmentPlanResponse plan,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            ReplayEnvironmentDemoSummaryResponse demo
    ) {
        List<String> values = new ArrayList<>();
        if (plan != null && plan.readiness() != null) {
            values.addAll(plan.readiness().blockers());
        }
        if (readiness != null) {
            values.addAll(readiness.blockers());
            values.addAll(readiness.runtimeDependencyBlockers());
        }
        if (demo != null) {
            values.addAll(demo.blockers());
            values.addAll(demo.runtimeDependencyBlockers());
        }
        return distinct(values);
    }

    private List<String> warnings(
            ReplayEnvironmentProvisionReadinessResponse readiness,
            ReplayEnvironmentDemoSummaryResponse demo
    ) {
        List<String> values = new ArrayList<>();
        if (readiness != null) {
            values.addAll(readiness.warnings());
            values.addAll(readiness.runtimeDependencyWarnings());
        }
        if (demo != null) {
            values.addAll(demo.runtimeDependencyWarnings());
        }
        return distinct(values);
    }

    private List<String> nextActions(
            ReplayEnvironmentPlanResponse plan,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            ReplayEnvironmentDemoSummaryResponse demo
    ) {
        List<String> values = new ArrayList<>();
        if (readiness != null) {
            values.addAll(readiness.requiredActions());
        }
        if (demo != null) {
            values.addAll(demo.nextActions());
        }
        if (plan != null) {
            values.addAll(plan.nextActions());
        }
        return distinct(values);
    }

    private String ownerFor(String dependencyType) {
        if ("DB".equalsIgnoreCase(dependencyType)) {
            return "DBA";
        }
        if ("HTTP".equalsIgnoreCase(dependencyType)
                || "EMAIL".equalsIgnoreCase(dependencyType)) {
            return "Backend";
        }
        if ("REDIS".equalsIgnoreCase(dependencyType)
                || "KAFKA".equalsIgnoreCase(dependencyType)
                || "ACTIVEMQ".equalsIgnoreCase(dependencyType)) {
            return "Infra";
        }
        return "Unknown";
    }

    private boolean include(Boolean value) {
        return value == null || value;
    }

    private List<String> warningsWith(
            List<String> base,
            List<String> additional
    ) {
        List<String> values = new ArrayList<>();
        if (base != null) {
            values.addAll(base);
        }
        if (additional != null) {
            values.addAll(additional);
        }
        return distinct(values);
    }

    private List<String> warningsWith(
            List<String> base,
            String additional
    ) {
        return warningsWith(base, List.of(additional));
    }

    private List<String> distinct(List<String> values) {
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(this::sanitizeText)
                    .forEach(safe::add);
        }
        return List.copyOf(safe);
    }

    private List<String> safeStrings(List<String> values) {
        return distinct(values == null ? List.of() : values);
    }

    @SuppressWarnings("unchecked")
    private Object safeObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = sanitizeText(String.valueOf(entry.getKey()));
                if (key.toLowerCase(Locale.ROOT).contains("reasoning")) {
                    continue;
                }
                Object safeValue = safeObject(entry.getValue());
                if (safeValue != null) {
                    safe.put(key, safeValue);
                }
            }
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            for (Object item : list) {
                Object safeValue = safeObject(item);
                if (safeValue != null) {
                    safe.add(safeValue);
                }
            }
            return safe;
        }
        if (value instanceof String string) {
            return sanitizeText(string);
        }
        return value;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        Object safe = safeObject(value == null ? Map.of() : value);
        if (safe instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String preview(Map<String, Object> advisory) {
        try {
            return truncate(
                    sanitizeText(objectMapper.writeValueAsString(advisory)),
                    800
            );
        } catch (Exception ignored) {
            return "";
        }
    }

    private String sanitizeText(String value) {
        String sanitized = firstNonBlank(value, "");
        sanitized = sanitized
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(access_token|refresh_token|id_token|password|secret|apiKey|privateKey)\\s*[:=]\\s*[^\\s,;}]+", "$1=[REDACTED]")
                .replaceAll("(?i)(authorization|set-cookie|cookie)\\s*[:=]\\s*[^\\r\\n,;}]+", "[REDACTED_HEADER]")
                .replaceAll("(?i)Authorization", "[credential-header]")
                .replaceAll("(?i)Set-Cookie", "[session-header]")
                .replaceAll("(?i)Cookie", "[session-header]")
                .replaceAll("(?is)reasoning_content\\s*[:=].*", "[REDACTED_REASONING_CONTENT]")
                .trim();
        return truncate(sanitized, 4_000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static class InvalidAdvisoryJsonException extends RuntimeException {
    }
}
