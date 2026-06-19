package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.service.EvidenceSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyLlmProviderClientTest {

    private static final String SECRET = "secret-company-token";

    private ReplayFixProperties properties;
    private EvidenceRepository evidenceRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new ReplayFixProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);
        properties.getAi().setModel("fallback-model");
        properties.getAi().getCompany().setBaseUrl("https://llm.example.test/v1");
        properties.getAi().getCompany().setEndpoint("/chat/completions");
        properties.getAi().getCompany().setModel("company-model");
        properties.getAi().getCompany().setToken(SECRET);
        properties.getAi().getCompany().setTimeoutMs(1000);
        properties.getAi().getCompany().setMaxInputChars(10000);
        properties.getAi().getCompany().setMaxOutputChars(2000);

        evidenceRepository = mock(EvidenceRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void shouldBuildSanitizedPayload() {
        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationRequest request = request(UUID.randomUUID());

        JsonNode payload = client.buildAnalysisPayload(
                request,
                "{\"Authorization\":\"Bearer raw-token\",\"password\":\"abc\",\"safe\":\"value\"}"
        );

        String payloadText = payload.toString();
        assertThat(payloadText).doesNotContain("raw-token");
        assertThat(payloadText).doesNotContain("password\":\"abc");
        assertThat(payloadText).contains("[REDACTED]");
        assertThat(payloadText).contains("Use only supplied ReplayFix evidence");
    }

    @Test
    void shouldParseSuccessfulResponse() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{\"jiraKey\":\"FIZZMS-10228\"}")));

        CompanyLlmProviderClient client = client(okResponse());
        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("COMPANY_LLM");
        assertThat(response.model()).isEqualTo("company-model");
        assertThat(response.structuredResponse().path("probableRootCause").asText())
                .isEqualTo("API validation mismatch");
        assertThat(response.structuredResponse().path("confidence").asDouble())
                .isEqualTo(0.42);
    }

    @Test
    void shouldHandleInvalidJsonGracefully() {
        UUID caseId = UUID.randomUUID();
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = client("""
                {"choices":[{"message":{"content":"not json"}}]}
                """);

        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.errorCategory()).isEqualTo("INVALID_JSON");
        assertThat(response.errorMessage()).doesNotContain(SECRET);
    }

    @Test
    void shouldHandleTimeoutGracefully() {
        UUID caseId = UUID.randomUUID();
        properties.getAi().getCompany().setTimeoutMs(1);
        when(evidenceRepository.findByCaseIdAndEvidenceType(caseId, EvidenceType.AI_INPUT_BUNDLE))
                .thenReturn(List.of(aiInputBundle("{}")));

        CompanyLlmProviderClient client = clientWithNeverResponse();
        AiGenerationResponse response = client.generate(request(caseId));

        assertThat(response.success()).isFalse();
        assertThat(response.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(response.errorMessage()).doesNotContain(SECRET);
    }

    @Test
    void shouldHandleUnauthorizedWithoutExposingToken() {
        CompanyLlmProviderClient client = clientWithStatus(
                HttpStatus.UNAUTHORIZED,
                "token=" + SECRET
        );

        AiConnectivityResult result = client.connectivity();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(401);
        assertThat(result.tokenConfigured()).isTrue();
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void shouldHandleForbiddenWithoutExposingToken() {
        CompanyLlmProviderClient client = clientWithStatus(
                HttpStatus.FORBIDDEN,
                "Authorization: Bearer " + SECRET
        );

        AiConnectivityResult result = client.connectivity();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(403);
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void shouldReportConnectivityWithoutTokenValue() {
        CompanyLlmProviderClient client = client(okResponse());

        AiConnectivityResult result = client.connectivity();

        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("COMPANY_LLM");
        assertThat(result.baseUrlConfigured()).isTrue();
        assertThat(result.tokenConfigured()).isTrue();
        assertThat(result.reachable()).isTrue();
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    private CompanyLlmProviderClient client(String body) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse
                        .create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()));
        return new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );
    }

    private CompanyLlmProviderClient clientWithStatus(
            HttpStatus status,
            String body
    ) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse
                        .create(status)
                        .header("Content-Type", "text/plain")
                        .body(body)
                        .build()));
        return new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );
    }

    private CompanyLlmProviderClient clientWithNeverResponse() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.never());
        return new CompanyLlmProviderClient(
                properties,
                evidenceRepository,
                new EvidenceSanitizer(),
                builder,
                objectMapper
        );
    }

    private String okResponse() {
        return """
                {
                  "id": "chatcmpl-test",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {
                        "content": "{\\"status\\":\\"HYPOTHESIS\\",\\"probableRootCause\\":\\"API validation mismatch\\",\\"confidence\\":0.42,\\"facts\\":[\\"fact\\"],\\"inferences\\":[\\"inference\\"],\\"unknowns\\":[\\"unknown\\"],\\"missingEvidence\\":[\\"trace\\"],\\"recommendedActions\\":[\\"review\\"],\\"warnings\\":[]}"
                      }
                    }
                  ]
                }
                """;
    }

    private AiGenerationRequest request(UUID caseId) {
        return new AiGenerationRequest(
                caseId,
                "FULL_INCIDENT_ANALYSIS",
                "system",
                "user",
                "company-model",
                0.1,
                2000,
                true,
                Map.of("jiraKey", "FIZZMS-10228")
        );
    }

    private EvidenceEntity aiInputBundle(String content) {
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setId(UUID.randomUUID());
        evidence.setEvidenceType(EvidenceType.AI_INPUT_BUNDLE);
        evidence.setContentText(content);
        evidence.setCreatedAt(Instant.now());
        evidence.setSanitized(true);
        return evidence;
    }
}
