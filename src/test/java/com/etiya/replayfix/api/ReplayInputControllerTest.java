package com.etiya.replayfix.api;

import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.domain.ReplayInputEntity;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.repository.ReplayInputRepository;
import com.etiya.replayfix.service.ReplayInputService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayInputControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReplayCaseRepository caseRepository;
    private ReplayInputRepository replayInputRepository;
    private MockMvc mockMvc;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseRepository = mock(ReplayCaseRepository.class);
        replayInputRepository = mock(ReplayInputRepository.class);
        ReplayInputService service = new ReplayInputService(
                caseRepository,
                replayInputRepository,
                objectMapper
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReplayInputController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        caseId = UUID.randomUUID();
        when(caseRepository.findById(caseId))
                .thenReturn(Optional.of(replayCase(caseId)));
        when(replayInputRepository.save(any(ReplayInputEntity.class)))
                .thenAnswer(invocation -> {
                    ReplayInputEntity entity = invocation.getArgument(0);
                    entity.setId(UUID.randomUUID());
                    return entity;
                });
    }

    @Test
    void postReplayInputWithoutSanitizedConfirmationReturns400()
            throws Exception {
        mockMvc.perform(post("/api/v1/cases/{caseId}/replay-inputs", caseId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endpointPath", "/DCE-CommerceBackend/orders",
                                "httpMethod", "POST",
                                "confirmSanitized", false
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("confirmSanitized")));
    }

    @Test
    void postReplayInputWithAuthorizationHeaderReturns400()
            throws Exception {
        mockMvc.perform(post("/api/v1/cases/{caseId}/replay-inputs", caseId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endpointPath", "/DCE-CommerceBackend/orders",
                                "httpMethod", "POST",
                                "sanitizedHeaders", Map.of(
                                        "Authorization", "Bearer raw-secret"
                                ),
                                "confirmSanitized", true
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("Authorization")))
                .andExpect(content().string(not(containsString(
                        "Bearer raw-secret"
                ))));
    }

    @Test
    void postReplayInputWithPasswordFieldReturns400()
            throws Exception {
        mockMvc.perform(post("/api/v1/cases/{caseId}/replay-inputs", caseId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endpointPath", "/DCE-CommerceBackend/orders",
                                "httpMethod", "POST",
                                "sanitizedRequestBody", Map.of(
                                        "password", "raw-password"
                                ),
                                "confirmSanitized", true
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(containsString(
                                "sanitizedRequestBody.password"
                        )))
                .andExpect(content().string(not(containsString(
                        "raw-password"
                ))));
    }

    @Test
    void postSanitizedReplayInputReturns201AndNoPayloadEcho()
            throws Exception {
        mockMvc.perform(post("/api/v1/cases/{caseId}/replay-inputs", caseId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endpointPath", "/DCE-CommerceBackend/orders",
                                "httpMethod", "POST",
                                "sanitizedHeaders", Map.of(
                                        "X-Correlation-Id", "trace-123"
                                ),
                                "sanitizedRequestBody", Map.of(
                                        "status", "PENDING",
                                        "token", "MASKED"
                                ),
                                "sanitizedQueryParams", Map.of(
                                        "channel", "web"
                                ),
                                "traceId", "trace-123",
                                "businessKey", "order:ORD-123",
                                "source", "MANUAL",
                                "confirmSanitized", true
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.endpointPath")
                        .value("/DCE-CommerceBackend/orders"))
                .andExpect(jsonPath("$.sanitized").value(true))
                .andExpect(jsonPath("$.containsSecrets").value(false))
                .andExpect(jsonPath("$.sanitizationWarnings[0]")
                        .value(containsString(
                                "sanitizedRequestBody.token"
                        )))
                .andExpect(content().string(not(containsString("PENDING"))))
                .andExpect(content().string(not(containsString("channel"))));
    }

    @Test
    void latestEndpointReturnsLatestInputWithoutRawPayload()
            throws Exception {
        ReplayInputEntity entity = replayInput(caseId);
        when(replayInputRepository.findFirstByCaseIdOrderByCreatedAtDesc(
                caseId
        )).thenReturn(Optional.of(entity));

        mockMvc.perform(get(
                        "/api/v1/cases/{caseId}/replay-inputs/latest",
                        caseId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entity.getId().toString()))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(content().string(not(containsString(
                        "sanitizedRequestBodyJson"
                ))))
                .andExpect(content().string(not(containsString(
                        "raw-password"
                ))));
    }

    private ReplayCaseEntity replayCase(UUID caseId) {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }

    private ReplayInputEntity replayInput(UUID caseId) {
        ReplayInputEntity entity = new ReplayInputEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setEndpointPath("/DCE-CommerceBackend/orders");
        entity.setHttpMethod("POST");
        entity.setTraceId("trace-123");
        entity.setBusinessKey("order:ORD-123");
        entity.setSource("MANUAL");
        entity.setSanitized(true);
        entity.setContainsSecrets(false);
        entity.setContainsPersonalData(false);
        entity.setSanitizationWarningsJson("[]");
        entity.setSanitizedRequestBodyJson("{\"password\":\"raw-password\"}");
        return entity;
    }
}
