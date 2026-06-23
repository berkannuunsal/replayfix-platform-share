package com.etiya.replayfix.api.dto;

import java.util.Map;

public record CreateReplayInputRequest(
        String endpointPath,
        String httpMethod,
        Map<String, Object> sanitizedHeaders,
        Map<String, Object> sanitizedRequestBody,
        Map<String, Object> sanitizedQueryParams,
        String traceId,
        String orderId,
        String customerId,
        String accountId,
        String businessKey,
        String source,
        boolean confirmSanitized
) {
}
