package com.etiya.replayfix.model;

import java.util.List;

public record FixPlanCandidate(
        String fixType,
        String targetFile,
        String targetClass,
        String targetMethod,
        String targetLayer,
        String relatedFlow,
        List<String> relatedSignals,
        String patchRuleId,
        String patchRuleName,
        String reason,
        String riskLevel,
        double confidence,
        String status,
        boolean approvalRequired,
        List<FixPlanEvidenceReference> evidenceReferences,
        List<String> warnings
) {
    public FixPlanCandidate {
        fixType = fixType == null ? "UNKNOWN" : fixType;
        targetFile = targetFile == null ? "" : targetFile;
        targetClass = targetClass == null ? "" : targetClass;
        targetMethod = targetMethod == null ? "" : targetMethod;
        targetLayer = targetLayer == null ? "UNKNOWN" : targetLayer;
        relatedFlow = relatedFlow == null ? "" : relatedFlow;
        relatedSignals = relatedSignals == null ? List.of() : List.copyOf(relatedSignals);
        patchRuleId = patchRuleId == null ? "" : patchRuleId;
        patchRuleName = patchRuleName == null ? "" : patchRuleName;
        reason = reason == null ? "" : reason;
        riskLevel = riskLevel == null ? "MEDIUM" : riskLevel;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        status = status == null ? "HYPOTHESIS" : status;
        evidenceReferences = evidenceReferences == null
                ? List.of()
                : List.copyOf(evidenceReferences);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
