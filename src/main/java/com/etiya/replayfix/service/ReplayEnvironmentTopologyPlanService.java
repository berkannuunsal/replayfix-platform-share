package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayComponentCatalogItem;
import com.etiya.replayfix.api.dto.ReplayEnvironmentTopologyComponentPlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentTopologyDependencyPlan;
import com.etiya.replayfix.api.dto.ReplayEnvironmentTopologyPlanResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayEnvironmentComponentHintEntity;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.repository.ReplayEnvironmentComponentHintRepository;
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
import java.util.UUID;

@Service
public class ReplayEnvironmentTopologyPlanService {

    private static final List<String> GUARDRAILS = List.of(
            "DRY_RUN_ONLY",
            "HUMAN_APPROVAL_REQUIRED",
            "NAMESPACE_SCOPED",
            "REPLAY_PREFIX_REQUIRED",
            "NO_SECRET_VALUE_READ",
            "NO_PRODUCTION_WRITE",
            "NO_ARGOCD_SYNC",
            "NO_KUBERNETES_APPLY",
            "TTL_CLEANUP_REQUIRED"
    );

    private final ReplayCaseRepository caseRepository;
    private final ReplayEnvironmentComponentHintRepository hintRepository;
    private final ReplayComponentCatalogService catalogService;
    private final ReplayFixProperties properties;

    public ReplayEnvironmentTopologyPlanService(
            ReplayCaseRepository caseRepository,
            ReplayEnvironmentComponentHintRepository hintRepository,
            ReplayComponentCatalogService catalogService,
            ReplayFixProperties properties
    ) {
        this.caseRepository = caseRepository;
        this.hintRepository = hintRepository;
        this.catalogService = catalogService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ReplayEnvironmentTopologyPlanResponse plan(
            UUID caseId,
            boolean includeUserHints,
            boolean includeSourceReasoning,
            boolean includeDefaultBackend
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        Map<String, String> requestedModes = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (includeDefaultBackend) {
            requestedModes.put("backend", "REPLAY_COPY");
        }
        if (includeUserHints) {
            for (ReplayEnvironmentComponentHintEntity hint
                    : hintRepository.findByCaseIdOrderByCreatedAtDesc(caseId)) {
                requestedModes.putIfAbsent(
                        catalogService.normalizeKey(hint.getComponentKey()),
                        catalogService.normalizeMode(hint.getRequestedMode())
                );
            }
        }
        if (includeSourceReasoning) {
            warnings.add("SOURCE_REASONING_TOPOLOGY_INFERENCE_NOT_IMPLEMENTED");
        }

        List<ReplayEnvironmentTopologyComponentPlan> components =
                new ArrayList<>();
        List<ReplayEnvironmentTopologyDependencyPlan> dependencies =
                new ArrayList<>();
        for (Map.Entry<String, String> entry : requestedModes.entrySet()) {
            String componentKey = entry.getKey();
            String requestedMode = entry.getValue();
            catalogService.find(componentKey).ifPresentOrElse(component -> {
                components.add(componentPlan(
                        replayCase,
                        component,
                        requestedMode
                ));
                dependencies.add(new ReplayEnvironmentTopologyDependencyPlan(
                        component.componentKey(),
                        requestedMode,
                        "Mode planned from catalog/user hint; no runtime dependency was contacted."
                ));
            }, () -> warnings.add("COMPONENT_CATALOG_ENTRY_MISSING:" + componentKey));
        }

        return new ReplayEnvironmentTopologyPlanResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                "HYPOTHESIS",
                namespace(replayCase),
                components,
                dependencies,
                GUARDRAILS,
                unique(warnings),
                true,
                true,
                Instant.now()
        );
    }

    private ReplayEnvironmentTopologyComponentPlan componentPlan(
            ReplayCaseEntity replayCase,
            ReplayComponentCatalogItem component,
            String requestedMode
    ) {
        List<String> missingConfig = missingConfig(component);
        return new ReplayEnvironmentTopologyComponentPlan(
                component.componentKey(),
                component.componentType(),
                replayApplicationName(replayCase.getJiraKey(), component.componentKey()),
                repository(component),
                component.defaultBranch(),
                component.helmChartPath(),
                component.valuesPath(),
                component.imageRepository(),
                requestedMode,
                missingConfig.isEmpty() ? "PLAN_READY" : "MISSING_CONFIG",
                missingConfig,
                GUARDRAILS
        );
    }

    private List<String> missingConfig(ReplayComponentCatalogItem component) {
        List<String> values = new ArrayList<>();
        if (isBlank(component.repositoryProject())) {
            values.add("repositoryProject");
        }
        if (isBlank(component.repositorySlug())) {
            values.add("repositorySlug");
        }
        if (isBlank(component.defaultBranch())) {
            values.add("defaultBranch");
        }
        if (isBlank(component.gitOpsRepo())) {
            values.add("gitOpsRepo");
        }
        if (isBlank(component.helmChartPath())) {
            values.add("helmChartPath");
        }
        if (isBlank(component.valuesPath())) {
            values.add("valuesPath");
        }
        if (isBlank(component.imageRepository())) {
            values.add("imageRepository");
        }
        return List.copyOf(values);
    }

    private String namespace(ReplayCaseEntity replayCase) {
        ReplayFixProperties.Target target =
                properties.getTargets().get(replayCase.getTargetKey());
        if (target != null && !isBlank(target.getPreCreatedReplayNamespace())) {
            return target.getPreCreatedReplayNamespace();
        }
        return "project-replay-sandbox";
    }

    private String replayApplicationName(String jiraKey, String componentKey) {
        return "replay-" + normalizeName(jiraKey) + "-" + normalizeName(componentKey);
    }

    private String normalizeName(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String repository(ReplayComponentCatalogItem component) {
        if (isBlank(component.repositoryProject())
                && isBlank(component.repositorySlug())) {
            return "";
        }
        return component.repositoryProject() + "/" + component.repositorySlug();
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null
                        ? List.of()
                        : values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .toList()
        ));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
