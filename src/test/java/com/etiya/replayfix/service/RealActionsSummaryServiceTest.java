package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.RealActionsSummaryResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealActionsSummaryServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private EvidenceRepository evidenceRepository;
    private ObjectMapper objectMapper;
    private RealActionsSummaryService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ReplayFixProperties properties = new ReplayFixProperties();
        properties.getRealActions().setEnabled(true);
        service = new RealActionsSummaryService(
                caseRepository,
                evidenceRepository,
                properties,
                objectMapper
        );
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
    }

    @Test
    void summaryIncludesCreatedJiraIssueBranchFlowAndPr() throws Exception {
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.JIRA_TEST_TASK
        )).thenReturn(List.of(evidence(
                EvidenceType.JIRA_TEST_TASK,
                jiraResponse()
        )));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.BITBUCKET_BRANCH_FLOW
        )).thenReturn(List.of(evidence(
                EvidenceType.BITBUCKET_BRANCH_FLOW,
                branchResponse()
        )));
        when(evidenceRepository.findByCaseIdAndEvidenceType(
                caseId,
                EvidenceType.PULL_REQUEST
        )).thenReturn(List.of(evidence(
                EvidenceType.PULL_REQUEST,
                prResponse()
        )));

        RealActionsSummaryResponse response = service.summary(caseId);

        assertThat(response.jiraTestTask().created()).isTrue();
        assertThat(response.jiraTestTask().issueKey()).isEqualTo("FIZZMS-9001");
        assertThat(response.bitbucketBranchFlow().executed()).isTrue();
        assertThat(response.bitbucketPullRequest().created()).isTrue();
        assertThat(response.guardrails()).contains("NO_SECRET_EXPOSURE");
    }

    private EvidenceEntity evidence(EvidenceType type, Object value)
            throws Exception {
        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(type);
        entity.setSource("test");
        entity.setSanitized(true);
        entity.setCreatedAt(Instant.now());
        entity.setContentText(objectMapper.writeValueAsString(value));
        return entity;
    }

    private JiraTestTaskResponse jiraResponse() {
        return new JiraTestTaskResponse(
                caseId,
                "FIZZMS-10228",
                false,
                true,
                "FIZZMS-9001",
                "https://jira/FIZZMS-9001",
                "FIZZMS-10228",
                "Task",
                "ReplayLab Test",
                "safe",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketBranchFlowResponse branchResponse() {
        return new BitbucketBranchFlowResponse(
                caseId,
                "FIZZMS-10228",
                false,
                true,
                "DCE",
                "backend",
                "master",
                "test2",
                "bugfix/FIZZMS-10228",
                "integration/test2/FIZZMS-10228",
                true,
                true,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BitbucketPullRequestResponse prResponse() {
        return new BitbucketPullRequestResponse(
                caseId,
                "FIZZMS-10228",
                false,
                true,
                "https://bitbucket/pr/42",
                "42",
                "DCE",
                "backend",
                "integration/test2/FIZZMS-10228",
                "test2",
                "[DRAFT] ReplayLab FIZZMS-10228",
                "safe",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("backend");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
