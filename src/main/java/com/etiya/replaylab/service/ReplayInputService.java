package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CreateReplayInputRequest;
import com.etiya.replaylab.api.dto.ReplayInputResponse;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayInputEntity;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayInputRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ReplayInputService {

    private static final Logger log = LoggerFactory.getLogger(
            ReplayInputService.class
    );
    private static final Set<String> HEADER_FIELDS_TO_REMOVE = Set.of(
            "authorization",
            "cookie",
            "setcookie"
    );
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "authorization",
            "cookie",
            "setcookie",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "password",
            "secret",
            "apikey",
            "privatekey",
            "cardnumber",
            "creditcardnumber",
            "personalidentitynumber"
    );
    private static final Set<String> VALID_SOURCES = Set.of(
            "MANUAL",
            "JIRA",
            "LOKI",
            "TEMPO",
            "ACCESS_LOG",
            "UNKNOWN"
    );

    private final ReplayCaseRepository caseRepository;
    private final ReplayInputRepository replayInputRepository;
    private final ObjectMapper objectMapper;

    public ReplayInputService(
            ReplayCaseRepository caseRepository,
            ReplayInputRepository replayInputRepository,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.replayInputRepository = replayInputRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReplayInputResponse create(
            UUID caseId,
            CreateReplayInputRequest request
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Replay case not found: " + caseId
                ));
        validate(request);
        ReplayInputEntity entity = new ReplayInputEntity();
        entity.setCaseId(caseId);
        entity.setJiraKey(replayCase.getJiraKey());
        entity.setTargetKey(replayCase.getTargetKey());
        entity.setEndpointPath(request.endpointPath());
        entity.setHttpMethod(normalizeHttpMethod(request.httpMethod()));
        entity.setSanitizedHeadersJson(toJson(emptyIfNull(
                request.sanitizedHeaders()
        )));
        entity.setSanitizedRequestBodyJson(toJson(emptyIfNull(
                request.sanitizedRequestBody()
        )));
        entity.setSanitizedQueryParamsJson(toJson(emptyIfNull(
                request.sanitizedQueryParams()
        )));
        entity.setTraceId(blankToNull(request.traceId()));
        entity.setOrderId(blankToNull(request.orderId()));
        entity.setCustomerId(blankToNull(request.customerId()));
        entity.setAccountId(blankToNull(request.accountId()));
        entity.setBusinessKey(blankToNull(request.businessKey()));
        entity.setSource(normalizeSource(request.source()));
        entity.setSanitized(true);
        entity.setContainsSecrets(false);
        entity.setContainsPersonalData(false);
        entity.setSanitizationWarningsJson(toJson(sanitizationWarnings(
                request
        )));

        ReplayInputEntity saved = replayInputRepository.save(entity);
        log.info(
                "REPLAY_INPUT_CREATED caseId={} replayInputId={} endpointPath={} httpMethod={}",
                caseId,
                saved.getId(),
                saved.getEndpointPath(),
                saved.getHttpMethod()
        );
        return ReplayInputResponse.from(saved, objectMapper);
    }

    @Transactional(readOnly = true)
    public List<ReplayInputResponse> list(UUID caseId) {
        return replayInputRepository.findByCaseIdOrderByCreatedAtDesc(caseId)
                .stream()
                .map(entity -> ReplayInputResponse.from(entity, objectMapper))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ReplayInputResponse> latest(UUID caseId) {
        return replayInputRepository
                .findFirstByCaseIdOrderByCreatedAtDesc(caseId)
                .map(entity -> ReplayInputResponse.from(entity, objectMapper));
    }

    private void validate(CreateReplayInputRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Replay input request is required");
        }
        List<String> errors = new ArrayList<>();
        if (!request.confirmSanitized()) {
            errors.add("confirmSanitized must be true");
        }
        validateHeaders(request.sanitizedHeaders(), errors);
        validateSensitiveFields(
                request.sanitizedRequestBody(),
                "sanitizedRequestBody",
                errors
        );
        validateSensitiveFields(
                request.sanitizedQueryParams(),
                "sanitizedQueryParams",
                errors
        );
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Replay input validation failed: "
                            + String.join("; ", new LinkedHashSet<>(errors))
            );
        }
    }

    private void validateHeaders(
            Map<String, Object> headers,
            List<String> errors
    ) {
        if (headers == null) {
            return;
        }
        for (String key : headers.keySet()) {
            String normalized = normalizeKey(key);
            if (HEADER_FIELDS_TO_REMOVE.contains(normalized)) {
                errors.add(
                        "Remove prohibited header before submission: "
                                + key
                );
            }
        }
        validateSensitiveFields(headers, "sanitizedHeaders", errors);
    }

    private void validateSensitiveFields(
            Object value,
            String path,
            List<String> errors
    ) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path + "." + key;
                if (isSensitiveFieldName(key)
                        && !isMaskedValue(entry.getValue())) {
                    errors.add(
                            "Sensitive field must be removed or masked: "
                                    + childPath
                    );
                }
                validateSensitiveFields(entry.getValue(), childPath, errors);
            }
        } else if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                validateSensitiveFields(item, path + "[" + index + "]", errors);
                index++;
            }
        }
    }

    private List<String> sanitizationWarnings(
            CreateReplayInputRequest request
    ) {
        List<String> warnings = new ArrayList<>();
        collectMaskedSensitiveFields(
                request.sanitizedRequestBody(),
                "sanitizedRequestBody",
                warnings
        );
        collectMaskedSensitiveFields(
                request.sanitizedQueryParams(),
                "sanitizedQueryParams",
                warnings
        );
        return List.copyOf(new LinkedHashSet<>(warnings));
    }

    private void collectMaskedSensitiveFields(
            Object value,
            String path,
            List<String> warnings
    ) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path + "." + key;
                if (isSensitiveFieldName(key)
                        && isMaskedValue(entry.getValue())) {
                    warnings.add("MASKED_SENSITIVE_FIELD:" + childPath);
                }
                collectMaskedSensitiveFields(
                        entry.getValue(),
                        childPath,
                        warnings
                );
            }
        } else if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                collectMaskedSensitiveFields(
                        item,
                        path + "[" + index + "]",
                        warnings
                );
                index++;
            }
        }
    }

    private boolean isSensitiveFieldName(String fieldName) {
        String normalized = normalizeKey(fieldName);
        if (SENSITIVE_FIELD_NAMES.contains(normalized)) {
            return true;
        }
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("privatekey")
                || normalized.contains("cardnumber")
                || normalized.contains("identitynumber");
    }

    private boolean isMaskedValue(Object value) {
        if (value == null) {
            return true;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return true;
        }
        String normalized = text.toUpperCase(Locale.ROOT);
        return "***".equals(text)
                || "MASKED".equals(normalized)
                || "REDACTED".equals(normalized)
                || "<REDACTED>".equals(normalized)
                || "[REDACTED]".equals(normalized)
                || text.matches("\\*{3,}");
    }

    private String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String normalizeHttpMethod(String method) {
        if (method == null || method.isBlank()) {
            return "POST";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return VALID_SOURCES.contains(normalized) ? normalized : "UNKNOWN";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Map<String, Object> emptyIfNull(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Replay input contains non-serializable JSON content"
            );
        }
    }
}
