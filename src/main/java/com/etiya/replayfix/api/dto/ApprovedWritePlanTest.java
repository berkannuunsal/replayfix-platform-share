package com.etiya.replayfix.api.dto;

import java.util.Map;

public record ApprovedWritePlanTest(
        String testType,
        String proposedClassName,
        String proposedMethodName,
        String scenario,
        String targetEndpoint,
        String status,
        boolean writeAllowed,
        Map<String, Object> metadata
) {
    public ApprovedWritePlanTest {
        testType = testType == null ? "UNKNOWN" : testType;
        proposedClassName = proposedClassName == null ? "" : proposedClassName;
        proposedMethodName = proposedMethodName == null
                ? ""
                : proposedMethodName;
        scenario = scenario == null ? "" : scenario;
        targetEndpoint = targetEndpoint == null ? "" : targetEndpoint;
        status = status == null ? "DRAFT" : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
