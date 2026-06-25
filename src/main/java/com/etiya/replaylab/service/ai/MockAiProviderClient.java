package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import com.etiya.replaylab.model.StructuredAiRootCauseAnalysis;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component("mockAiProvider")
public class MockAiProviderClient implements AiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MockAiProviderClient.class);
    private static final double MOCK_MAX_CONFIDENCE = 0.75;

    private final ReplayLabProperties properties;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public MockAiProviderClient(
            ReplayLabProperties properties,
            EvidenceRepository evidenceRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConnectivityResult connectivity() {
        String model = properties.getAi().getModel();
        if (model == null || model.isBlank()) {
            model = "mock-replaylab-v1";
        }

        return new AiConnectivityResult(
                true,
                true,
                "MOCK",
                model,
                true,
                true,
                true,
                true,
                true,
                200,
                0L,
                null,
                List.of("Synthetic AI provider for local validation.")
        );
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            List<EvidenceEntity> evidenceList = evidenceRepository.findByCaseId(request.caseId());
            
            List<UUID> supportingEvidenceIds = evidenceList.stream()
                    .filter(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE ||
                                 e.getEvidenceType() == EvidenceType.LOKI_LOGS ||
                                 e.getEvidenceType() == EvidenceType.TEMPO_TRACE ||
                                 e.getEvidenceType() == EvidenceType.REPOSITORY_RESOLUTION ||
                                 e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT)
                    .map(EvidenceEntity::getId)
                    .collect(Collectors.toList());

            boolean hasJira = evidenceList.stream()
                    .anyMatch(e -> e.getEvidenceType() == EvidenceType.JIRA_ISSUE);
            
            boolean hasLoki = evidenceList.stream()
                    .anyMatch(e -> e.getEvidenceType() == EvidenceType.LOKI_LOGS);
            
            boolean hasTempo = evidenceList.stream()
                    .anyMatch(e -> e.getEvidenceType() == EvidenceType.TEMPO_TRACE);

            double baseConfidence = 0.45;
            if (hasJira && hasLoki && hasTempo) {
                baseConfidence = 0.72;
            } else if (hasJira && hasLoki) {
                baseConfidence = 0.65;
            } else if (hasJira) {
                baseConfidence = 0.35;
            }

            baseConfidence = Math.min(baseConfidence, MOCK_MAX_CONFIDENCE);

            StructuredAiRootCauseAnalysis analysis = new StructuredAiRootCauseAnalysis(
                    request.caseId(),
                    "MOCK_AI_ANALYSIS",
                    "The incident is consistent with an authorization or token propagation failure.",
                    "Authorization header mapping or token propagation failure.",
                    "sample-order-service",
                    baseConfidence,
                    List.of(
                            "1. Jira reports HTTP 401 during order completion.",
                            "2. Loki evidence confirms unauthorized response.",
                            "3. Tempo evidence indicates the failure in the downstream service chain.",
                            "4. Business flow remains incomplete."
                    ),
                    supportingEvidenceIds.stream().map(UUID::toString).collect(Collectors.toList()),
                    List.of(
                            "Misconfigured OAuth client credentials",
                            "Token expiration not handled",
                            "Service mesh authorization policy mismatch"
                    ),
                    List.of(
                            "Reproduce the completion request with missing or invalid authorization context.",
                            "Assert HTTP 401 and incomplete workflow state.",
                            "Apply the proposed fix and verify successful completion."
                    ),
                    List.of(
                            "Review authorization header forwarding and token exchange configuration.",
                            "Verify downstream client credentials and audience mapping."
                    ),
                    List.of(
                            "Incident-time Kubernetes runtime version",
                            "Verified failing regression test",
                            "Real LLM provider analysis"
                    ),
                    "Review the evidence bundle and confirm the authorization propagation path.",
                    "MOCK",
                    request.model(),
                    true,
                    List.of("Synthetic AI response for local validation.")
            );

            JsonNode structuredResponse = objectMapper.valueToTree(analysis);
            long latencyMs = System.currentTimeMillis() - startTime;

            return new AiGenerationResponse(
                    true,
                    "MOCK",
                    request.model(),
                    UUID.randomUUID().toString(),
                    "completed",
                    latencyMs,
                    request.userPrompt().length(),
                    structuredResponse.toString().length(),
                    structuredResponse,
                    List.of("Synthetic AI response for local validation."),
                    null,
                    null
            );

        } catch (Exception e) {
            log.error("MOCK AI provider error: {}", e.getMessage());
            return new AiGenerationResponse(
                    false,
                    "MOCK",
                    request.model(),
                    null,
                    "error",
                    System.currentTimeMillis() - startTime,
                    0,
                    0,
                    null,
                    List.of(),
                    "MOCK_ERROR",
                    "Mock provider failed: " + e.getMessage()
            );
        }
    }

    @Override
    public String providerName() {
        return "MOCK";
    }

    @Override
    public boolean supportsStructuredOutput() {
        return true;
    }
}
