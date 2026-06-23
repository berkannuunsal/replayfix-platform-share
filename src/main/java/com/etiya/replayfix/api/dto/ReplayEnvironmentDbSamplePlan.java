package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentDbSamplePlan(
        String domain,
        String schema,
        String tableName,
        List<String> keyFields,
        String sampleQueryTemplate,
        boolean readOnlyRequired,
        boolean productionWriteAllowed,
        List<String> sanitizationRules,
        List<String> expectedMockResponseFields,
        List<String> missingFields
) {
}
