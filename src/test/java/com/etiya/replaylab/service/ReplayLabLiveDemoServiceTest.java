package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabEnvironmentBlueprintRequest;
import com.etiya.replaylab.api.dto.ReplayLabEnvironmentBlueprintResponse;
import com.etiya.replaylab.api.dto.ReplayLabHumanEvidenceRequest;
import com.etiya.replaylab.api.dto.ReplayLabLiveDemoStateResponse;
import com.etiya.replaylab.api.dto.ReplayLabRcaResponse;
import com.etiya.replaylab.api.dto.ReplayLabTokenUsageEstimateResponse;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayLabLiveDemoServiceTest {

    private UUID caseId;
    private ReplayCaseEntity replayCase;
    private List<EvidenceEntity> evidenceStore;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ObjectMapper objectMapper;
    private ReplayLabLiveDemoService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("FIZZMS-10228");
        replayCase.setTargetKey("backend");
        replayCase.setEnvironment("test2");
        replayCase.setSynthetic(true);
        replayCase.setStatus(ReplayCaseStatus.NEW);
        evidenceStore = new ArrayList<>();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        when(caseRepository.findById(caseId)).thenAnswer(invocation -> Optional.ofNullable(replayCase));
        when(caseRepository.save(any(ReplayCaseEntity.class))).thenAnswer(invocation -> {
            replayCase = invocation.getArgument(0);
            return replayCase;
        });
        when(evidenceRepository.findByCaseId(caseId)).thenAnswer(invocation -> List.copyOf(evidenceStore));
        when(evidenceRepository.save(any(EvidenceEntity.class))).thenAnswer(invocation -> {
            EvidenceEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(Instant.now());
            }
            evidenceStore.add(entity);
            return entity;
        });

        ReplayLabDoraImpactScoreboardService doraService =
                new ReplayLabDoraImpactScoreboardService(caseRepository);
        ReplayLabRemediationReadinessService readinessService =
                new ReplayLabRemediationReadinessService(caseRepository, evidenceRepository);
        ReplayLabFinalRemediationBriefService briefService =
                new ReplayLabFinalRemediationBriefService(
                        caseRepository,
                        evidenceRepository,
                        doraService,
                        readinessService
                );
        service = new ReplayLabLiveDemoService(
                caseRepository,
                evidenceRepository,
                doraService,
                readinessService,
                briefService,
                new EvidenceSanitizer(),
                objectMapper
        );
    }

    @Test
    void startLiveDemoReturnsInitialState() {
        ReplayLabLiveDemoStateResponse response = service.start(caseId);

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.defectKey()).isEqualTo("FIZZMS-10228");
        assertThat(response.currentStep()).isEqualTo("START");
        assertThat(response.completedSteps()).containsExactly("START");
        assertThat(response.unlockedSteps()).contains("START", "EVIDENCE");
    }

    @Test
    void collectEvidenceReturnsRequiredEvidenceSources() {
        ReplayLabLiveDemoStateResponse response = service.collectEvidence(caseId);

        assertThat(response.evidence())
                .extracting("source")
                .contains(
                        "Jira",
                        "Jenkins",
                        "Bitbucket",
                        "Loki",
                        "Tempo",
                        "Source Context",
                        "Rovo",
                        "AGENTS.md"
                );
        assertThat(response.completedSteps()).contains("EVIDENCE");
    }

    @Test
    void humanEvidenceTitleDefaultsWhenBlank() {
        ReplayLabLiveDemoStateResponse response = service.addHumanEvidence(
                caseId,
                new ReplayLabHumanEvidenceRequest("Log", " ", "User-provided context", "")
        );

        assertThat(response.humanEvidence()).hasSize(1);
        assertThat(response.humanEvidence().get(0).summary()).isEqualTo("Human evidence #1");
        assertThat(response.agentEvents())
                .extracting("message")
                .contains("Human Evidence Added");
    }

    @Test
    void tokenUsageReturnsEstimateMode() {
        ReplayLabTokenUsageEstimateResponse response = service.tokenUsage(caseId);

        assertThat(response.mode()).isEqualTo("ESTIMATE");
        assertThat(response.totalEstimatedTokens()).isEqualTo(9670);
        assertThat(response.notes()).contains("Usage is estimated for demo visibility.");
    }

    @Test
    void generateRcaReturnsHypothesisWhenEvidenceIsLimited() {
        ReplayLabRcaResponse response = service.generateRca(caseId);

        assertThat(response.status()).isEqualTo("HYPOTHESIS");
        assertThat(response.confidence()).isEqualTo("EVIDENCE_LIMITED");
        assertThat(response.probableRootCause()).contains("Region, tax_info and timezone consistency");
    }

    @Test
    void environmentPlanIsDryRunAndDoesNotExecuteProvisioning() {
        ReplayLabEnvironmentBlueprintResponse response = service.planEnvironment(
                caseId,
                new ReplayLabEnvironmentBlueprintRequest(
                        "berkan",
                        false,
                        List.of("backend", "Jenkins build context"),
                        List.of("Customer UI")
                )
        );

        assertThat(response.dryRun()).isTrue();
        assertThat(response.status()).isEqualTo("BLUEPRINT_READY");
        assertThat(response.provisioningExecuted()).isFalse();
        assertThat(response.guardrails()).contains("No infrastructure changes executed");
    }

    @Test
    void environmentSkipUnlocksAgentsStep() {
        service.collectEvidence(caseId);
        service.generateRca(caseId);

        ReplayLabLiveDemoStateResponse response = service.skipEnvironment(caseId);

        assertThat(response.completedSteps()).contains("ENVIRONMENT");
        assertThat(response.unlockedSteps()).contains("PREFLIGHT");
        assertThat(response.agentEvents())
                .extracting("message")
                .contains("Replay environment skipped by user");
    }

    @Test
    void finalStateIncludesGuardrails() {
        service.collectEvidence(caseId);
        service.generateRca(caseId);
        service.skipEnvironment(caseId);

        ReplayLabLiveDemoStateResponse response = service.finalState(caseId);

        assertThat(response.currentStep()).isEqualTo("BRIEF");
        assertThat(response.guardrails())
                .contains(
                        "No PR auto-merge",
                        "No automatic deployment",
                        "No Jenkins trigger executed",
                        "No Jira issue or task created"
                );
        assertThat(response.finalBriefMarkdown()).contains("ReplayLab Final Remediation Brief");
    }

    @Test
    void responseValuesDoNotExposeSensitiveStrings() throws Exception {
        service.addHumanEvidence(
                caseId,
                new ReplayLabHumanEvidenceRequest(
                        "Other",
                        "Authorization header",
                        "Authorization: Bearer abc; cookie=session; secret=abc",
                        "https://user:password@example.test/path"
                )
        );
        ReplayLabLiveDemoStateResponse response = service.finalState(caseId);

        List<String> values = new ArrayList<>();
        collectTextValues(objectMapper.valueToTree(response), values);
        assertThat(values)
                .allSatisfy(value -> assertThat(value.toLowerCase())
                        .doesNotContain("authorization")
                        .doesNotContain("cookie")
                        .doesNotContain("secret")
                        .doesNotContain("bearer")
                        .doesNotContain("password"));
    }

    @Test
    void startDoesNotCallExternalWriteRepositoriesBeyondLocalCaseSaveWhenCaseExists() {
        service.start(caseId);

        verify(evidenceRepository, never()).save(any());
    }

    private void collectTextValues(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            values.add(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectTextValues(item, values));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectTextValues(entry.getValue(), values));
        }
    }
}
