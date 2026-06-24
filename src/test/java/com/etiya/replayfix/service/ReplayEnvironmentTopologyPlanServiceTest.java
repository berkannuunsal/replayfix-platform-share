package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayEnvironmentTopologyPlanResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.domain.ReplayEnvironmentComponentHintEntity;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.etiya.replayfix.repository.ReplayEnvironmentComponentHintRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayEnvironmentTopologyPlanServiceTest {

    private UUID caseId;
    private ReplayEnvironmentComponentHintRepository hintRepository;
    private ReplayEnvironmentTopologyPlanService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        ReplayCaseRepository caseRepository = mock(ReplayCaseRepository.class);
        hintRepository = mock(ReplayEnvironmentComponentHintRepository.class);
        ReplayFixProperties properties =
                ReplayEnvironmentComponentHintServiceTest.properties();
        ReplayFixProperties.Target target = new ReplayFixProperties.Target();
        target.setPreCreatedReplayNamespace("project-replay-sandbox");
        properties.getTargets().put("bss-monolith", target);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase()));
        service = new ReplayEnvironmentTopologyPlanService(
                caseRepository,
                hintRepository,
                new ReplayComponentCatalogService(properties),
                properties
        );
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void topologyPlanIncludesDefaultBackendAndUserHints() {
        when(hintRepository.findByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(List.of(
                        hint("mco-backend", "REPLAY_COPY"),
                        hint("serdoo-ui", "REPLAY_COPY"),
                        hint("wso2", "TEST2_SHARED")
                ));

        ReplayEnvironmentTopologyPlanResponse response =
                service.plan(caseId, true, true, true);

        assertThat(response.namespace()).isEqualTo("project-replay-sandbox");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.components())
                .extracting("componentKey")
                .contains("backend", "mco-backend", "serdoo-ui", "wso2");
        assertThat(response.guardrails()).contains(
                "NO_ARGOCD_SYNC",
                "NO_KUBERNETES_APPLY",
                "HUMAN_APPROVAL_REQUIRED"
        );
    }

    @Test
    void applicationNamesUseReplayPrefix() {
        when(hintRepository.findByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(List.of(hint("mco-backend", "REPLAY_COPY")));

        ReplayEnvironmentTopologyPlanResponse response =
                service.plan(caseId, true, false, true);

        assertThat(response.components())
                .allSatisfy(component -> assertThat(
                        component.replayApplicationName()
                ).startsWith("replay-"));
        assertThat(response.components())
                .extracting("replayApplicationName")
                .contains("replay-fizzms-10228-mco-backend");
    }

    @Test
    void noSecretValuesAreExposedAndNoExternalActionsAreTriggered()
            throws Exception {
        when(hintRepository.findByCaseIdOrderByCreatedAtDesc(caseId))
                .thenReturn(List.of(hint("wso2", "TEST2_SHARED")));

        ReplayEnvironmentTopologyPlanResponse response =
                service.plan(caseId, true, false, true);

        assertThat(objectMapper.writeValueAsString(response))
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie");
        verify(hintRepository).findByCaseIdOrderByCreatedAtDesc(caseId);
        verify(hintRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.CONTEXT_READY);
        return entity;
    }

    private ReplayEnvironmentComponentHintEntity hint(
            String componentKey,
            String requestedMode
    ) {
        ReplayEnvironmentComponentHintEntity entity =
                new ReplayEnvironmentComponentHintEntity();
        entity.setCaseId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setComponentKey(componentKey);
        entity.setRequestedMode(requestedMode);
        entity.setReason("manual");
        return entity;
    }
}
