package com.etiya.replaylab.api.dto;

import java.util.Map;

public record ApprovedWritePlanFile(
        String fileType,
        String path,
        String targetClass,
        String targetMethod,
        String targetEndpoint,
        String recommendedChangeType,
        String status,
        boolean writeAllowed,
        Map<String, Object> metadata
) {
    public ApprovedWritePlanFile {
        fileType = fileType == null ? "UNKNOWN" : fileType;
        path = path == null ? "" : path;
        targetClass = targetClass == null ? "" : targetClass;
        targetMethod = targetMethod == null ? "" : targetMethod;
        targetEndpoint = targetEndpoint == null ? "" : targetEndpoint;
        recommendedChangeType = recommendedChangeType == null
                ? "UNKNOWN"
                : recommendedChangeType;
        status = status == null ? "DRAFT" : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
