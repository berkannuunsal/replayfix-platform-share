package com.etiya.replaylab.service;

import com.etiya.replaylab.model.PatchRuleReference;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PatchRuleRegistry {

    public static final String VALIDATION_GUARD = "VALIDATION_GUARD";
    public static final String MAPPING_FIX = "MAPPING_FIX";
    public static final String NULL_SAFETY = "NULL_SAFETY";
    public static final String CONFIG_FALLBACK = "CONFIG_FALLBACK";
    public static final String ENUM_NORMALIZATION = "ENUM_NORMALIZATION";
    public static final String DATE_TIMEZONE_NORMALIZATION =
            "DATE_TIMEZONE_NORMALIZATION";
    public static final String DB_STATE_VALIDATION = "DB_STATE_VALIDATION";
    public static final String EXCEPTION_HANDLING = "EXCEPTION_HANDLING";
    public static final String REQUEST_FIELD_SANITIZATION =
            "REQUEST_FIELD_SANITIZATION";
    public static final String SQL_QUERY_FILTER_FIX = "SQL_QUERY_FILTER_FIX";
    public static final String CACHE_EVICTION_FIX = "CACHE_EVICTION_FIX";
    public static final String ASYNC_RETRY_GUARD = "ASYNC_RETRY_GUARD";

    private final Map<String, PatchRuleReference> rules;

    public PatchRuleRegistry() {
        Map<String, PatchRuleReference> values = new LinkedHashMap<>();
        register(values, VALIDATION_GUARD, "Validation guard",
                "Add or adjust request/domain validation before state mutation.",
                List.of("CONTROLLER", "SERVICE", "SERVICE_IMPL", "VALIDATOR"),
                List.of("SOURCE_REASONING", "JIRA_ISSUE", "DB_EVIDENCE"),
                "MEDIUM");
        register(values, MAPPING_FIX, "Mapping fix",
                "Correct mapping between request DTO fields and persisted domain state.",
                List.of("SERVICE", "SERVICE_IMPL", "MAPPER", "DTO"),
                List.of("SOURCE_REASONING", "JIRA_ISSUE", "DB_EVIDENCE"),
                "MEDIUM");
        register(values, NULL_SAFETY, "Null safety",
                "Guard optional or nullable values without changing business semantics.",
                List.of("CONTROLLER", "SERVICE", "SERVICE_IMPL", "MAPPER", "DTO"),
                List.of("SOURCE_REASONING", "STACK_TRACE"),
                "LOW");
        register(values, CONFIG_FALLBACK, "Config fallback",
                "Add bounded fallback behavior for missing or invalid configuration.",
                List.of("CONFIG", "SERVICE", "SERVICE_IMPL"),
                List.of("SOURCE_REASONING", "CONFIG_EVIDENCE"),
                "MEDIUM");
        register(values, ENUM_NORMALIZATION, "Enum normalization",
                "Normalize enum-like request values before validation or persistence.",
                List.of("CONTROLLER", "SERVICE", "SERVICE_IMPL", "DTO", "MAPPER"),
                List.of("SOURCE_REASONING", "JIRA_ISSUE"),
                "MEDIUM");
        register(values, DATE_TIMEZONE_NORMALIZATION,
                "Date/timezone normalization",
                "Normalize date or timezone values before downstream use.",
                List.of("CONTROLLER", "SERVICE", "SERVICE_IMPL", "MAPPER"),
                List.of("SOURCE_REASONING", "JIRA_ISSUE", "DB_EVIDENCE"),
                "MEDIUM");
        register(values, DB_STATE_VALIDATION, "DB state validation",
                "Validate current database state before applying a state transition.",
                List.of("SERVICE", "SERVICE_IMPL", "REPOSITORY"),
                List.of("SOURCE_REASONING", "DB_EVIDENCE"),
                "HIGH");
        register(values, EXCEPTION_HANDLING, "Exception handling",
                "Handle a known failure path while preserving existing contracts.",
                List.of("CONTROLLER", "SERVICE", "SERVICE_IMPL"),
                List.of("SOURCE_REASONING", "STACK_TRACE", "LOG_EVIDENCE"),
                "MEDIUM");
        register(values, REQUEST_FIELD_SANITIZATION,
                "Request field sanitization",
                "Normalize or reject invalid request fields at the boundary.",
                List.of("CONTROLLER", "DTO", "VALIDATOR", "SERVICE_IMPL"),
                List.of("SOURCE_REASONING", "JIRA_ISSUE"),
                "MEDIUM");
        register(values, SQL_QUERY_FILTER_FIX, "SQL query filter fix",
                "Adjust query filters to avoid selecting incorrect records.",
                List.of("REPOSITORY", "SERVICE", "SERVICE_IMPL"),
                List.of("SOURCE_REASONING", "DB_EVIDENCE"),
                "HIGH");
        register(values, CACHE_EVICTION_FIX, "Cache eviction fix",
                "Invalidate or refresh stale cached state after a mutation.",
                List.of("SERVICE", "SERVICE_IMPL", "CONFIG"),
                List.of("SOURCE_REASONING", "CACHE_EVIDENCE"),
                "MEDIUM");
        register(values, ASYNC_RETRY_GUARD, "Async retry guard",
                "Bound retries or duplicate async processing for a known path.",
                List.of("SERVICE", "SERVICE_IMPL"),
                List.of("SOURCE_REASONING", "LOG_EVIDENCE"),
                "MEDIUM");
        rules = Map.copyOf(values);
    }

    public List<PatchRuleReference> rules() {
        return List.copyOf(rules.values());
    }

    public Optional<PatchRuleReference> findById(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    private void register(
            Map<String, PatchRuleReference> values,
            String ruleId,
            String name,
            String description,
            List<String> allowedLayers,
            List<String> requiredEvidenceTypes,
            String riskLevel
    ) {
        values.put(ruleId, new PatchRuleReference(
                ruleId,
                name,
                description,
                allowedLayers,
                requiredEvidenceTypes,
                riskLevel,
                true,
                false
        ));
    }
}
