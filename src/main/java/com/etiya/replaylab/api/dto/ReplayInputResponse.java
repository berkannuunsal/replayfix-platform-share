package com.etiya.replaylab.api.dto;

import com.etiya.replaylab.domain.ReplayInputEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReplayInputResponse(
        UUID id,
        UUID caseId,
        String jiraKey,
        String targetKey,
        String endpointPath,
        String httpMethod,
        String traceId,
        String orderId,
        String customerId,
        String accountId,
        String businessKey,
        String source,
        boolean sanitized,
        boolean containsSecrets,
        boolean containsPersonalData,
        List<String> sanitizationWarnings,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReplayInputResponse from(
            ReplayInputEntity entity,
            ObjectMapper objectMapper
    ) {
        return new ReplayInputResponse(
                entity.getId(),
                entity.getCaseId(),
                entity.getJiraKey(),
                entity.getTargetKey(),
                entity.getEndpointPath(),
                entity.getHttpMethod(),
                entity.getTraceId(),
                entity.getOrderId(),
                entity.getCustomerId(),
                entity.getAccountId(),
                entity.getBusinessKey(),
                entity.getSource(),
                entity.isSanitized(),
                entity.isContainsSecrets(),
                entity.isContainsPersonalData(),
                warnings(entity, objectMapper),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private static List<String> warnings(
            ReplayInputEntity entity,
            ObjectMapper objectMapper
    ) {
        String json = entity.getSanitizationWarningsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {}
            );
        } catch (Exception ignored) {
            return List.of("SANITIZATION_WARNINGS_UNREADABLE");
        }
    }
}
