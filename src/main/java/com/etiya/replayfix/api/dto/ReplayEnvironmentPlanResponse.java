package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReplayEnvironmentPlanResponse(
        UUID caseId,
        String jiraKey,
        String targetKey,
        String status,
        String summary,
        ReplayArgoCdInventoryContext argoCdInventory,
        ReplayEnvironmentComponentPlan backend,
        ReplayEnvironmentComponentPlan customerUi,
        ReplayEnvironmentNamespacePlan namespacePlan,
        ReplayEnvironmentDbStrategyPlan dbStrategyPlan,
        ReplayEnvironmentAccessRoutingPlan accessRoutingPlan,
        ReplayEnvironmentStateContinuationPlan stateContinuationPlan,
        List<ReplayEnvironmentMockDependencyPlan> mockDependencies,
        List<ReplayEnvironmentDbSamplePlan> dbSamplePlans,
        List<ReplayRuntimeDependencyPlan> runtimeDependencies,
        ReplayEnvironmentDryRunBundle dryRunBundle,
        ReplayEnvironmentReadiness readiness,
        List<String> requiredApprovals,
        List<String> missingEvidence,
        List<String> guardrails,
        List<String> nextActions,
        Instant generatedAt
) {
    public ReplayEnvironmentPlanResponse(
            UUID caseId,
            String jiraKey,
            String targetKey,
            String status,
            String summary,
            ReplayArgoCdInventoryContext argoCdInventory,
            ReplayEnvironmentComponentPlan backend,
            ReplayEnvironmentComponentPlan customerUi,
            ReplayEnvironmentNamespacePlan namespacePlan,
            ReplayEnvironmentDbStrategyPlan dbStrategyPlan,
            ReplayEnvironmentAccessRoutingPlan accessRoutingPlan,
            ReplayEnvironmentStateContinuationPlan stateContinuationPlan,
            List<ReplayEnvironmentMockDependencyPlan> mockDependencies,
            List<ReplayEnvironmentDbSamplePlan> dbSamplePlans,
            ReplayEnvironmentDryRunBundle dryRunBundle,
            ReplayEnvironmentReadiness readiness,
            List<String> requiredApprovals,
            List<String> missingEvidence,
            List<String> guardrails,
            List<String> nextActions,
            Instant generatedAt
    ) {
        this(
                caseId,
                jiraKey,
                targetKey,
                status,
                summary,
                argoCdInventory,
                backend,
                customerUi,
                namespacePlan,
                dbStrategyPlan,
                accessRoutingPlan,
                stateContinuationPlan,
                mockDependencies,
                dbSamplePlans,
                List.of(),
                dryRunBundle,
                readiness,
                requiredApprovals,
                missingEvidence,
                guardrails,
                nextActions,
                generatedAt
        );
    }
}
