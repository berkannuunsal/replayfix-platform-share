package com.etiya.replayfix.model;

import java.util.List;

public record PatchRuleReference(
        String ruleId,
        String name,
        String description,
        List<String> allowedLayers,
        List<String> requiredEvidenceTypes,
        String riskLevel,
        boolean requiresHumanApproval,
        boolean canWriteCode
) {
    public PatchRuleReference {
        ruleId = ruleId == null ? "" : ruleId;
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        allowedLayers = allowedLayers == null ? List.of() : List.copyOf(allowedLayers);
        requiredEvidenceTypes = requiredEvidenceTypes == null
                ? List.of()
                : List.copyOf(requiredEvidenceTypes);
        riskLevel = riskLevel == null ? "MEDIUM" : riskLevel;
    }
}
