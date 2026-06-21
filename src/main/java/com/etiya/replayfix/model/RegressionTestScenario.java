package com.etiya.replayfix.model;

import java.util.List;

public record RegressionTestScenario(
        String name,
        String testType,
        String targetEndpoint,
        String targetClass,
        String targetMethod,
        List<String> preconditions,
        String action,
        String expectedResult,
        List<RegressionTestStep> steps,
        List<RegressionTestAssertion> assertions,
        List<String> warnings
) {
    public RegressionTestScenario {
        name = name == null ? "" : name;
        testType = testType == null ? "UNKNOWN" : testType;
        targetEndpoint = targetEndpoint == null ? "" : targetEndpoint;
        targetClass = targetClass == null ? "" : targetClass;
        targetMethod = targetMethod == null ? "" : targetMethod;
        preconditions = preconditions == null
                ? List.of()
                : List.copyOf(preconditions);
        action = action == null ? "" : action;
        expectedResult = expectedResult == null ? "" : expectedResult;
        steps = steps == null ? List.of() : List.copyOf(steps);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
