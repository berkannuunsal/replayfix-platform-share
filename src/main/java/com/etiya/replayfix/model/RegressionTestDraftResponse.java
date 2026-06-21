package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record RegressionTestDraftResponse(
        UUID caseId,
        String jiraKey,
        String status,
        List<String> testTypeCandidates,
        String selectedTestType,
        String targetEndpoint,
        String targetClass,
        String targetMethod,
        List<RegressionTestScenario> scenarios,
        List<RegressionTestDataRequirement> dataRequirements,
        List<RegressionTestMockRequirement> mockRequirements,
        List<RegressionTestDbValidationRequirement> dbValidationRequirements,
        boolean requiresDbEvidence,
        boolean requiresHumanApproval,
        List<String> warnings
) {
    public RegressionTestDraftResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        testTypeCandidates = testTypeCandidates == null
                ? List.of()
                : List.copyOf(testTypeCandidates);
        selectedTestType = selectedTestType == null
                ? "UNKNOWN"
                : selectedTestType;
        targetEndpoint = targetEndpoint == null ? "" : targetEndpoint;
        targetClass = targetClass == null ? "" : targetClass;
        targetMethod = targetMethod == null ? "" : targetMethod;
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        dataRequirements = dataRequirements == null
                ? List.of()
                : List.copyOf(dataRequirements);
        mockRequirements = mockRequirements == null
                ? List.of()
                : List.copyOf(mockRequirements);
        dbValidationRequirements = dbValidationRequirements == null
                ? List.of()
                : List.copyOf(dbValidationRequirements);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
