package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReplayEnvironmentTopologyPlanResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String namespace,
        List<ReplayEnvironmentTopologyComponentPlan> components,
        List<ReplayEnvironmentTopologyDependencyPlan> dependencies,
        List<String> guardrails,
        List<String> warnings,
        boolean requiresHumanApproval,
        boolean dryRun,
        Instant generatedAt
) {
    public ReplayEnvironmentTopologyPlanResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        namespace = namespace == null ? "" : namespace;
        components = components == null ? List.of() : List.copyOf(components);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
