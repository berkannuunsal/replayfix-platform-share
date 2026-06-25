package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintInput;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintsRequest;
import com.etiya.replaylab.api.dto.ReplayEnvironmentComponentHintsResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayEnvironmentComponentHintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayEnvironmentComponentHintServiceTest {

    private UUID caseId;
    private ReplayEnvironmentComponentHintRepository hintRepository;
    private ReplayEnvironmentComponentHintService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        ReplayCaseRepository caseRepository = mock(ReplayCaseRepository.class);
        hintRepository = mock(ReplayEnvironmentComponentHintRepository.class);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(replayCase()));
        service = new ReplayEnvironmentComponentHintService(
                caseRepository,
                hintRepository,
                new ReplayComponentCatalogService(properties())
        );
    }

    @Test
    void acceptsConfiguredReplayCopyHints() {
        ReplayEnvironmentComponentHintsResponse response =
                service.addHints(caseId, new ReplayEnvironmentComponentHintsRequest(
                        List.of(
                                new ReplayEnvironmentComponentHintInput(
                                        "mco-backend",
                                        "REPLAY_COPY",
                                        "MCO validation"
                                ),
                                new ReplayEnvironmentComponentHintInput(
                                        "serdoo-ui",
                                        "REPLAY_COPY",
                                        "UI trigger"
                                ),
                                new ReplayEnvironmentComponentHintInput(
                                        "wso2",
                                        "TEST2_SHARED",
                                        "Shared gateway"
                                )
                        ),
                        "manual know-how"
                ));

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.acceptedHints())
                .extracting("componentKey")
                .contains("mco-backend", "serdoo-ui", "wso2");
        verify(hintRepository, org.mockito.Mockito.times(3)).save(any());
    }

    @Test
    void rejectsUnknownComponentKey() {
        ReplayEnvironmentComponentHintsResponse response =
                service.addHints(caseId, new ReplayEnvironmentComponentHintsRequest(
                        List.of(new ReplayEnvironmentComponentHintInput(
                                "unknown-service",
                                "REPLAY_COPY",
                                "unknown"
                        )),
                        ""
                ));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectedHints())
                .extracting("message")
                .contains("UNKNOWN_COMPONENT_KEY");
        verify(hintRepository, never()).save(any());
    }

    @Test
    void rejectsModeNotAllowedByComponentConfig() {
        ReplayEnvironmentComponentHintsResponse response =
                service.addHints(caseId, new ReplayEnvironmentComponentHintsRequest(
                        List.of(new ReplayEnvironmentComponentHintInput(
                                "wso2",
                                "REPLAY_COPY",
                                "not allowed"
                        )),
                        ""
                ));

        assertThat(response.rejectedHints())
                .extracting("message")
                .contains("REQUESTED_MODE_NOT_ALLOWED");
        verify(hintRepository, never()).save(any());
    }

    private ReplayCaseEntity replayCase() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("bss-monolith");
        entity.setStatus(ReplayCaseStatus.CONTEXT_READY);
        return entity;
    }

    static ReplayLabProperties properties() {
        ReplayLabProperties properties = new ReplayLabProperties();
        add(properties, "backend", "SPRING_BOOT_BACKEND", "backend",
                List.of("REPLAY_COPY", "TEST2_SHARED"));
        add(properties, "mco-backend", "SPRING_BOOT_BACKEND", "mco-backend",
                List.of("REPLAY_COPY", "TEST2_SHARED", "FUTURE_PHASE"));
        add(properties, "serdoo-ui", "ANGULAR_FRONTEND", "serdoo-ui",
                List.of("REPLAY_COPY", "TEST2_SHARED", "FUTURE_PHASE"));
        add(properties, "wso2", "WSO2_API", "wso2",
                List.of("TEST2_SHARED", "FUTURE_PHASE"));
        return properties;
    }

    private static void add(
            ReplayLabProperties properties,
            String key,
            String type,
            String repositorySlug,
            List<String> modes
    ) {
        ReplayLabProperties.ReplayComponent component =
                new ReplayLabProperties.ReplayComponent();
        component.setComponentKey(key);
        component.setDisplayName(key);
        component.setComponentType(type);
        component.setRepositoryProject("DCE");
        component.setRepositorySlug(repositorySlug);
        component.setDefaultBranch("test2");
        component.setGitOpsRepo("https://bitbucket.company.com/scm/fdi/helm-charts.git");
        component.setHelmChartPath(key);
        component.setValuesPath("project-test2-values.yaml");
        component.setImageRepository("image/" + key);
        component.setDefaultNamespace("project-test-" + key);
        component.setDependencyModes(modes);
        component.setAllowReplay(true);
        component.setRequiresApproval(true);
        properties.getComponents().put(key, component);
    }
}
