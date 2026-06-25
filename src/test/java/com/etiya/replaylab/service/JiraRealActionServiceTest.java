package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.JiraTestTaskRequest;
import com.etiya.replaylab.api.dto.JiraTestTaskResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.integration.JiraClient;
import com.etiya.replaylab.model.IntegrationModels.JiraIssueCreateResult;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JiraRealActionServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private JiraClient jiraClient;
    private JiraRealActionService service;
    private ReplayLabProperties properties;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        jiraClient = mock(JiraClient.class);
        properties = new ReplayLabProperties();
        service = new JiraRealActionService(
                caseRepository,
                evidenceRepository,
                jiraClient,
                mock(TestExecutionPlanService.class),
                mock(CodeChangeAdvisoryService.class),
                mock(GoldenPathEvidenceSnapshotService.class),
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void jiraTestTaskPreviewDoesNotCallJiraCreate() {
        JiraTestTaskResponse response = service.preview(caseId, request(false, false));

        assertThat(response.previewOnly()).isTrue();
        assertThat(response.created()).isFalse();
        assertThat(response.summary()).contains("ReplayLab", "FIZZMS-8346");
        verify(jiraClient, never()).createIssue(any());
    }

    @Test
    void jiraCreateRequiresConfirmationAndGuardrails() {
        assertThatThrownBy(() -> service.create(caseId, request(false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONFIRM_CREATE_REQUIRED")
                .hasMessageContaining("GUARDRAILS_ACCEPTED_REQUIRED");
        verify(jiraClient, never()).createIssue(any());
    }

    @Test
    void jiraCreateDisabledReturnsRealActionsDisabled() {
        assertThatThrownBy(() -> service.create(caseId, request(true, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REAL_ACTIONS_DISABLED");
        verify(jiraClient, never()).createIssue(any());
    }

    @Test
    void jiraCreateSuccessStoresCreatedIssueKey() {
        properties.getRealActions().setEnabled(true);
        properties.getRealActions().setJiraCreateEnabled(true);
        when(jiraClient.createIssue(any())).thenReturn(
                new JiraIssueCreateResult(
                        true,
                        "FIZZMS-9001",
                        "https://jira/browse/FIZZMS-9001",
                        201,
                        List.of()
                )
        );

        JiraTestTaskResponse response = service.create(caseId, request(true, true));

        assertThat(response.created()).isTrue();
        assertThat(response.createdIssueKey()).isEqualTo("FIZZMS-9001");
        assertThat(response.descriptionPreview())
                .doesNotContain("token")
                .doesNotContain("password");
        verify(evidenceRepository).save(any(EvidenceEntity.class));
    }

    private JiraTestTaskRequest request(boolean confirm, boolean guardrails) {
        return new JiraTestTaskRequest(
                "berkan",
                "FIZZMS-8346",
                "Task",
                false,
                "ReplayLab Test Execution",
                false,
                false,
                false,
                confirm,
                guardrails
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-8346");
        entity.setTargetKey("backend");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
