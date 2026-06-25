package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ApproveReplayEnvironmentRequest;
import com.etiya.replayfix.api.dto.CreateReplayEnvironmentRequestResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentDemoSummaryResponse;
import com.etiya.replayfix.api.dto.RejectReplayEnvironmentRequest;
import com.etiya.replayfix.api.dto.ReplayEnvironmentPlanResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentProvisioningDisabledResponse;
import com.etiya.replayfix.api.dto.ReplayEnvironmentRequestResponse;
import com.etiya.replayfix.api.dto.ReplayRuntimeDependencyPlan;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayEnvironmentRequestEntity;
import com.etiya.replayfix.repository.ReplayEnvironmentRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReplayEnvironmentRequestService {

    public static final String PROVISIONING_DISABLED_MESSAGE =
            "Real ArgoCD provisioning is not enabled yet.";
    public static final String PROVISIONING_DISABLED_NEXT_ACTION =
            "Real ArgoCD provisioning is disabled until infra credentials and approval gates are configured.";

    private static final Logger log = LoggerFactory.getLogger(
            ReplayEnvironmentRequestService.class
    );

    private final ReplayEnvironmentPlanService planService;
    private final ReplayEnvironmentRequestRepository requestRepository;
    private final ObjectMapper objectMapper;
    private final ReplayFixProperties properties;

    public ReplayEnvironmentRequestService(
            ReplayEnvironmentPlanService planService,
            ReplayEnvironmentRequestRepository requestRepository,
            ObjectMapper objectMapper,
            ReplayFixProperties properties
    ) {
        this.planService = planService;
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public CreateReplayEnvironmentRequestResponse create(
            UUID caseId,
            boolean includeCustomerUi,
            String mockMode,
            String requestedBy
    ) {
        ReplayEnvironmentPlanResponse plan = planService.plan(
                caseId,
                includeCustomerUi,
                mockMode
        );
        ReplayEnvironmentRequestEntity entity =
                new ReplayEnvironmentRequestEntity();
        entity.setCaseId(plan.caseId());
        entity.setJiraKey(plan.jiraKey());
        entity.setTargetKey(plan.targetKey());
        entity.setStatus(initialStatus(plan));
        entity.setRequestedBy(blankToNull(requestedBy));
        entity.setPlanSnapshotJson(toJson(plan));
        entity.setReplayNamespace(plan.namespacePlan() == null
                ? null
                : plan.namespacePlan().proposedReplayNamespace());
        entity.setProposedHost(plan.accessRoutingPlan() == null
                ? null
                : plan.accessRoutingPlan().proposedHost());
        entity.setDryRunOnly(true);
        entity.setRealProvisioningEnabled(false);

        ReplayEnvironmentRequestEntity saved = requestRepository.save(entity);
        log.info(
                "REPLAY_ENV_REQUEST_CREATED requestId={} caseId={} status={} jiraKey={} targetKey={}",
                saved.getId(),
                saved.getCaseId(),
                saved.getStatus(),
                saved.getJiraKey(),
                saved.getTargetKey()
        );
        return new CreateReplayEnvironmentRequestResponse(
                response(saved, plan),
                plan
        );
    }

    @Transactional(readOnly = true)
    public ReplayEnvironmentRequestResponse get(UUID requestId) {
        ReplayEnvironmentRequestEntity entity = request(requestId);
        return response(entity, readPlan(entity));
    }

    @Transactional(readOnly = true)
    public ReplayEnvironmentPlanResponse getPlan(UUID requestId) {
        return readPlan(request(requestId));
    }

    @Transactional
    public ReplayEnvironmentRequestResponse approve(
            UUID requestId,
            ApproveReplayEnvironmentRequest request
    ) {
        if (request == null || !request.acceptGuardrails()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "acceptGuardrails must be true before approval"
            );
        }
        ReplayEnvironmentRequestEntity entity = request(requestId);
        if ("REJECTED".equals(entity.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Rejected replay environment requests cannot be approved"
            );
        }
        entity.setStatus("APPROVED");
        entity.setApprovedBy(blankToDefault(
                request.approvedBy(),
                "unknown"
        ));
        entity.setApprovedAt(Instant.now());
        entity.setApprovalNote(blankToNull(request.approvalNote()));
        ReplayEnvironmentRequestEntity saved = requestRepository.save(entity);
        log.info(
                "REPLAY_ENV_REQUEST_APPROVED requestId={} caseId={} status={} jiraKey={} targetKey={}",
                saved.getId(),
                saved.getCaseId(),
                saved.getStatus(),
                saved.getJiraKey(),
                saved.getTargetKey()
        );
        return response(saved, readPlan(saved), List.of(
                PROVISIONING_DISABLED_NEXT_ACTION
        ));
    }

    @Transactional
    public ReplayEnvironmentRequestResponse reject(
            UUID requestId,
            RejectReplayEnvironmentRequest request
    ) {
        ReplayEnvironmentRequestEntity entity = request(requestId);
        entity.setStatus("REJECTED");
        entity.setRejectedBy(blankToDefault(
                request == null ? null : request.rejectedBy(),
                "unknown"
        ));
        entity.setRejectedAt(Instant.now());
        entity.setRejectionReason(blankToNull(
                request == null ? null : request.rejectionReason()
        ));
        ReplayEnvironmentRequestEntity saved = requestRepository.save(entity);
        log.info(
                "REPLAY_ENV_REQUEST_REJECTED requestId={} caseId={} status={} jiraKey={} targetKey={}",
                saved.getId(),
                saved.getCaseId(),
                saved.getStatus(),
                saved.getJiraKey(),
                saved.getTargetKey()
        );
        return response(saved, readPlan(saved));
    }

    @Transactional
    public ReplayEnvironmentProvisioningDisabledResponse provision(
            UUID requestId
    ) {
        ReplayEnvironmentRequestEntity entity = request(requestId);
        entity.setStatus("PROVISIONING_DISABLED");
        entity.setDryRunOnly(true);
        entity.setRealProvisioningEnabled(false);
        ReplayEnvironmentRequestEntity saved = requestRepository.save(entity);
        log.warn(
                "REPLAY_ENV_PROVISIONING_DISABLED requestId={} caseId={} status={} jiraKey={} targetKey={}",
                saved.getId(),
                saved.getCaseId(),
                saved.getStatus(),
                saved.getJiraKey(),
                saved.getTargetKey()
        );
        ReplayEnvironmentPlanResponse plan = readPlan(saved);
        return new ReplayEnvironmentProvisioningDisabledResponse(
                response(saved, plan, List.of(PROVISIONING_DISABLED_NEXT_ACTION)),
                PROVISIONING_DISABLED_MESSAGE,
                List.of(PROVISIONING_DISABLED_NEXT_ACTION)
        );
    }

    @Transactional(readOnly = true)
    public ReplayEnvironmentProvisionReadinessResponse provisionReadiness(
            UUID requestId
    ) {
        ReplayEnvironmentRequestEntity entity = request(requestId);
        ReplayEnvironmentPlanResponse plan = readPlan(entity);
        ReplayFixProperties.Target target = properties.getTargets()
                .getOrDefault(entity.getTargetKey(), new ReplayFixProperties.Target());
        ReplayFixProperties.ArgoCd argocd = properties.getArgocd();

        boolean requestApproved = "APPROVED".equals(entity.getStatus());
        boolean realProvisioningEnabled = argocd.isRealProvisioningEnabled();
        boolean namespaceConfigured = !isBlank(entity.getReplayNamespace());
        boolean argocdCredentialsConfigured =
                argocd.isEnabled()
                        && !isBlank(argocd.getBaseUrl())
                        && tokenConfigured(argocd);
        boolean argocdProjectConfigured = !isBlank(firstNonBlank(
                argocd.getProject(),
                target.getArgocdProject(),
                plan.argoCdInventory() == null
                        ? ""
                        : plan.argoCdInventory().argocdProject()
        ));
        boolean ingressConfigured = !isBlank(entity.getProposedHost())
                && plan.accessRoutingPlan() != null;
        boolean secretStrategyConfigured = !isBlank(target.getSecretStrategy());
        boolean dbStrategyConfirmed = dbStrategyConfirmed(plan, target);
        boolean mockServerConfigured = mockServerConfigured(plan, target);
        boolean cleanupConfigured = cleanupConfigured(plan, target);
        List<String> runtimeDependencyBlockers =
                runtimeDependencyBlockers(plan);
        List<String> runtimeDependencyWarnings =
                runtimeDependencyWarnings(plan);
        boolean runtimeDependenciesSafe = runtimeDependencyBlockers.isEmpty();

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!requestApproved) {
            blockers.add("REQUEST_NOT_APPROVED");
        }
        if (!realProvisioningEnabled) {
            blockers.add("REAL_PROVISIONING_DISABLED");
        }
        if (!namespaceConfigured) {
            blockers.add("REPLAY_NAMESPACE_NOT_CONFIGURED");
        }
        if (!argocdCredentialsConfigured) {
            blockers.add("ARGOCD_CREDENTIALS_NOT_CONFIGURED");
        }
        if (!argocdProjectConfigured) {
            blockers.add("ARGOCD_PROJECT_NOT_CONFIGURED");
        }
        if (!ingressConfigured) {
            blockers.add("INGRESS_NOT_CONFIGURED");
        }
        if (!secretStrategyConfigured) {
            blockers.add("SECRET_STRATEGY_NOT_CONFIGURED");
        }
        if (!dbStrategyConfirmed) {
            blockers.add("DB_STRATEGY_NOT_CONFIRMED");
        }
        if (!mockServerConfigured) {
            blockers.add("MOCK_SERVER_NOT_CONFIGURED");
        }
        if (!cleanupConfigured) {
            blockers.add("CLEANUP_NOT_CONFIGURED");
        }
        blockers.addAll(runtimeDependencyBlockers);
        warnings.addAll(runtimeDependencyWarnings);
        if (!argocd.isEnabled()) {
            warnings.add("ARGOCD_CONFIG_DISABLED");
        }
        if (!isBlank(argocd.getTokenEnvVarName())
                && System.getenv(argocd.getTokenEnvVarName()) == null
                && !argocd.isTokenConfigured()) {
            warnings.add("ARGOCD_TOKEN_ENV_VAR_NOT_PRESENT");
        }

        String readinessStatus = readinessStatus(blockers);
        log.info(
                "REPLAY_ENV_PROVISION_READINESS requestId={} caseId={} status={} readinessStatus={} jiraKey={} targetKey={}",
                entity.getId(),
                entity.getCaseId(),
                entity.getStatus(),
                readinessStatus,
                entity.getJiraKey(),
                entity.getTargetKey()
        );
        return new ReplayEnvironmentProvisionReadinessResponse(
                entity.getId(),
                entity.getCaseId(),
                entity.getJiraKey(),
                entity.getTargetKey(),
                entity.getStatus(),
                readinessStatus,
                requestApproved,
                realProvisioningEnabled,
                entity.isDryRunOnly(),
                namespaceConfigured,
                argocdCredentialsConfigured,
                argocdProjectConfigured,
                ingressConfigured,
                secretStrategyConfigured,
                dbStrategyConfirmed,
                mockServerConfigured,
                cleanupConfigured,
                runtimeDependenciesSafe,
                entity.getReplayNamespace(),
                entity.getProposedHost(),
                List.copyOf(new LinkedHashSet<>(blockers)),
                List.copyOf(new LinkedHashSet<>(warnings)),
                runtimeDependencyBlockers,
                runtimeDependencyWarnings,
                requiredActions(blockers),
                plan.guardrails(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public ReplayEnvironmentDemoSummaryResponse demoSummary(UUID requestId) {
        ReplayEnvironmentRequestEntity entity = request(requestId);
        ReplayEnvironmentPlanResponse plan = readPlan(entity);
        ReplayEnvironmentProvisionReadinessResponse readiness =
                provisionReadiness(requestId);
        String backendReplayApp = plan.backend() == null
                ? null
                : plan.backend().replayArgoCdApplicationName();
        String customerUiReplayApp = plan.customerUi() == null
                ? null
                : plan.customerUi().replayArgoCdApplicationName();
        String accessMode = plan.accessRoutingPlan() == null
                ? null
                : plan.accessRoutingPlan().mode();
        String dbStrategy = plan.dbStrategyPlan() == null
                ? null
                : plan.dbStrategyPlan().strategy();
        boolean sanitizedInputAttached = sanitizedInputAttached(plan);
        List<String> blockers = readiness.blockers();
        List<String> nextActions = new ArrayList<>();
        nextActions.addAll(readiness.requiredActions());
        if (nextActions.isEmpty()) {
            nextActions.addAll(plan.nextActions());
        }
        return new ReplayEnvironmentDemoSummaryResponse(
                entity.getJiraKey(),
                entity.getTargetKey(),
                entity.getCaseId(),
                entity.getId(),
                entity.getStatus(),
                plan.status(),
                readiness.readinessStatus(),
                backendReplayApp,
                customerUiReplayApp,
                entity.getReplayNamespace(),
                entity.getProposedHost(),
                accessMode,
                dbStrategy,
                sanitizedInputAttached,
                readiness.runtimeDependenciesSafe(),
                List.copyOf(new LinkedHashSet<>(blockers)),
                readiness.runtimeDependencyBlockers(),
                readiness.runtimeDependencyWarnings(),
                plan.guardrails(),
                List.copyOf(new LinkedHashSet<>(nextActions)),
                demoNarrative(
                        entity,
                        plan,
                        readiness,
                        sanitizedInputAttached
                )
        );
    }

    private String initialStatus(ReplayEnvironmentPlanResponse plan) {
        if ("READY_FOR_APPROVAL".equals(plan.status())
                && plan.readiness() != null
                && plan.readiness().blockers().isEmpty()) {
            return "APPROVAL_REQUIRED";
        }
        return "PLAN_READY";
    }

    private boolean sanitizedInputAttached(
            ReplayEnvironmentPlanResponse plan
    ) {
        if (plan.argoCdInventory() != null
                && plan.argoCdInventory().rawHints() != null
                && plan.argoCdInventory().rawHints()
                        .containsKey("sanitizedReplayInput")) {
            return true;
        }
        return plan.stateContinuationPlan() != null
                && plan.stateContinuationPlan().requiredSanitizedInputs()
                        .stream()
                        .anyMatch(value -> value != null
                                && value.toLowerCase()
                                        .contains("sanitized replay input"));
    }

    private String demoNarrative(
            ReplayEnvironmentRequestEntity entity,
            ReplayEnvironmentPlanResponse plan,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            boolean sanitizedInputAttached
    ) {
        return "ReplayLab has prepared a dry-run replay environment plan for "
                + entity.getJiraKey()
                + " on "
                + entity.getTargetKey()
                + ". The backend replay app is "
                + safeText(plan.backend() == null
                        ? null
                        : plan.backend().replayArgoCdApplicationName())
                + ", targeting namespace "
                + safeText(entity.getReplayNamespace())
                + " and host "
                + safeText(entity.getProposedHost())
                + ". Sanitized replay input is "
                + (sanitizedInputAttached ? "attached" : "not attached")
                + ". Provision readiness is "
                + readiness.readinessStatus()
                + "; real provisioning remains governed by approval and infrastructure guardrails.";
    }

    private String safeText(String value) {
        return isBlank(value) ? "not configured" : value;
    }

    private String readinessStatus(List<String> blockers) {
        if (blockers.isEmpty()) {
            return "READY";
        }
        if (blockers.contains("REAL_PROVISIONING_DISABLED")
                || blockers.contains("REQUEST_NOT_APPROVED")) {
            return "BLOCKED";
        }
        return "NOT_READY";
    }

    private List<String> requiredActions(List<String> blockers) {
        List<String> actions = new ArrayList<>();
        if (blockers.contains("REQUEST_NOT_APPROVED")) {
            actions.add("Approve the replay environment request");
        }
        if (blockers.contains("REAL_PROVISIONING_DISABLED")) {
            actions.add(PROVISIONING_DISABLED_NEXT_ACTION);
        }
        if (blockers.contains("ARGOCD_CREDENTIALS_NOT_CONFIGURED")) {
            actions.add("Configure ArgoCD base URL and token readiness flag or token env var");
        }
        if (blockers.contains("SECRET_STRATEGY_NOT_CONFIGURED")) {
            actions.add("Configure target secretStrategy without exposing secret values");
        }
        if (blockers.contains("DB_STRATEGY_NOT_CONFIRMED")) {
            actions.add("Confirm DB strategy for the target before provisioning");
        }
        if (blockers.contains("CLEANUP_NOT_CONFIGURED")) {
            actions.add("Configure TTL cleanup metadata or target cleanupTtl");
        }
        if (blockers.stream().anyMatch(value -> value.startsWith("RUNTIME_"))
                || blockers.contains("TEST2_DB_WRITE_NOT_CONFIRMED")
                || blockers.contains("ACTIVEMQ_CONSUMER_NOT_DISABLED")
                || blockers.contains("KAFKA_CONSUMER_NOT_DISABLED")) {
            actions.add("Resolve runtime dependency blockers before provisioning");
        }
        return List.copyOf(new LinkedHashSet<>(actions));
    }

    private List<String> runtimeDependencyBlockers(
            ReplayEnvironmentPlanResponse plan
    ) {
        List<String> blockers = new ArrayList<>();
        List<ReplayRuntimeDependencyPlan> dependencies =
                plan.runtimeDependencies() == null
                        ? List.of()
                        : plan.runtimeDependencies();
        for (ReplayRuntimeDependencyPlan dependency : dependencies) {
            blockers.addAll(dependency.blockers());
        }
        return List.copyOf(new LinkedHashSet<>(blockers));
    }

    private List<String> runtimeDependencyWarnings(
            ReplayEnvironmentPlanResponse plan
    ) {
        List<String> warnings = new ArrayList<>();
        List<ReplayRuntimeDependencyPlan> dependencies =
                plan.runtimeDependencies() == null
                        ? List.of()
                        : plan.runtimeDependencies();
        for (ReplayRuntimeDependencyPlan dependency : dependencies) {
            warnings.addAll(dependency.warnings());
        }
        return List.copyOf(new LinkedHashSet<>(warnings));
    }

    private boolean tokenConfigured(ReplayFixProperties.ArgoCd argocd) {
        if (argocd.isTokenConfigured()) {
            return true;
        }
        String envVar = argocd.getTokenEnvVarName();
        return !isBlank(envVar) && !isBlank(System.getenv(envVar));
    }

    private boolean dbStrategyConfirmed(
            ReplayEnvironmentPlanResponse plan,
            ReplayFixProperties.Target target
    ) {
        String strategy = plan.dbStrategyPlan() == null
                ? ""
                : plan.dbStrategyPlan().strategy();
        return !"TEST2_SHARED_DB".equals(strategy)
                || target.isDbStrategyConfirmed();
    }

    private boolean mockServerConfigured(
            ReplayEnvironmentPlanResponse plan,
            ReplayFixProperties.Target target
    ) {
        if (!isBlank(target.getMockServerChartPath())) {
            return true;
        }
        if (plan.dryRunBundle() == null) {
            return false;
        }
        return notEmpty(plan.dryRunBundle().mockServerManifest());
    }

    private boolean cleanupConfigured(
            ReplayEnvironmentPlanResponse plan,
            ReplayFixProperties.Target target
    ) {
        if (!isBlank(target.getCleanupTtl())) {
            return true;
        }
        if (plan.dryRunBundle() == null) {
            return false;
        }
        return notEmpty(plan.dryRunBundle().ttlCleanupMetadata());
    }

    private boolean notEmpty(Map<String, Object> map) {
        return map != null && !map.isEmpty();
    }

    private ReplayEnvironmentRequestEntity request(UUID requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay environment request not found: " + requestId
                ));
    }

    private ReplayEnvironmentRequestResponse response(
            ReplayEnvironmentRequestEntity entity,
            ReplayEnvironmentPlanResponse plan
    ) {
        return response(entity, plan, defaultNextActions(entity));
    }

    private ReplayEnvironmentRequestResponse response(
            ReplayEnvironmentRequestEntity entity,
            ReplayEnvironmentPlanResponse plan,
            List<String> nextActions
    ) {
        return ReplayEnvironmentRequestResponse.from(
                entity,
                plan == null ? List.of() : plan.requiredApprovals(),
                blockers(plan),
                plan == null ? List.of() : plan.guardrails(),
                nextActions
        );
    }

    private List<String> blockers(ReplayEnvironmentPlanResponse plan) {
        if (plan == null || plan.readiness() == null) {
            return List.of();
        }
        return plan.readiness().blockers();
    }

    private List<String> defaultNextActions(
            ReplayEnvironmentRequestEntity entity
    ) {
        if ("APPROVED".equals(entity.getStatus())
                || "PROVISIONING_DISABLED".equals(entity.getStatus())) {
            return List.of(PROVISIONING_DISABLED_NEXT_ACTION);
        }
        if ("APPROVAL_REQUIRED".equals(entity.getStatus())) {
            return List.of("Review guardrails and approve before any provisioning step");
        }
        if ("REJECTED".equals(entity.getStatus())) {
            return List.of("Revise the replay environment plan and create a new request");
        }
        return List.of("Resolve blockers before approval");
    }

    private ReplayEnvironmentPlanResponse readPlan(
            ReplayEnvironmentRequestEntity entity
    ) {
        try {
            return objectMapper.readValue(
                    entity.getPlanSnapshotJson(),
                    ReplayEnvironmentPlanResponse.class
            );
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored replay environment plan snapshot could not be read"
            );
        }
    }

    private String toJson(ReplayEnvironmentPlanResponse plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Replay environment plan snapshot could not be stored"
            );
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
