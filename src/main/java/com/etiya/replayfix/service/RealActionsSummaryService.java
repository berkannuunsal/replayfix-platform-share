package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.BitbucketBranchFlowResponse;
import com.etiya.replayfix.api.dto.BitbucketPullRequestResponse;
import com.etiya.replayfix.api.dto.JiraTestTaskResponse;
import com.etiya.replayfix.api.dto.RealActionsSummaryResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RealActionsSummaryService {

    private static final List<String> GUARDRAILS = List.of(
            "CONFIRM_CREATE_REQUIRED",
            "GUARDRAILS_ACCEPTED_REQUIRED",
            "HUMAN_APPROVAL_REQUIRED",
            "NO_DIRECT_PROTECTED_BRANCH_PUSH",
            "NO_AUTO_PR_MERGE",
            "NO_AUTO_JENKINS",
            "NO_SECRET_EXPOSURE"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public RealActionsSummaryService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RealActionsSummaryResponse summary(UUID caseId) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        Optional<JiraTestTaskResponse> jiraTask =
                latest(caseId, EvidenceType.JIRA_TEST_TASK)
                        .flatMap(item -> read(item, JiraTestTaskResponse.class));
        Optional<BitbucketBranchFlowResponse> branchFlow =
                latest(caseId, EvidenceType.BITBUCKET_BRANCH_FLOW)
                        .flatMap(item -> read(
                                item,
                                BitbucketBranchFlowResponse.class
                        ));
        Optional<BitbucketPullRequestResponse> pr =
                latest(caseId, EvidenceType.PULL_REQUEST)
                        .flatMap(item -> read(
                                item,
                                BitbucketPullRequestResponse.class
                        ));

        return new RealActionsSummaryResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                properties.getRealActions().isEnabled(),
                new RealActionsSummaryResponse.JiraTestTask(
                        jiraTask.map(JiraTestTaskResponse::created)
                                .orElse(false),
                        jiraTask.map(JiraTestTaskResponse::createdIssueKey)
                                .orElse(""),
                        jiraTask.map(JiraTestTaskResponse::createdIssueUrl)
                                .orElse(""),
                        jiraTask.map(value -> value.created()
                                ? "CREATED"
                                : "NOT_CREATED").orElse("NOT_CREATED")
                ),
                new RealActionsSummaryResponse.BitbucketBranchFlow(
                        branchFlow.map(BitbucketBranchFlowResponse::executed)
                                .orElse(false),
                        branchFlow.map(BitbucketBranchFlowResponse::bugfixBranch)
                                .orElse(""),
                        branchFlow.map(BitbucketBranchFlowResponse::integrationBranch)
                                .orElse(replayCase.getGeneratedBranch() == null
                                        ? ""
                                        : replayCase.getGeneratedBranch()),
                        branchFlow.map(BitbucketBranchFlowResponse::mergeSucceeded)
                                .orElse(false),
                        branchFlow.map(value -> value.executed()
                                ? "EXECUTED"
                                : "NOT_EXECUTED").orElse("NOT_EXECUTED")
                ),
                new RealActionsSummaryResponse.BitbucketPullRequest(
                        pr.map(BitbucketPullRequestResponse::created)
                                .orElse(false),
                        pr.map(BitbucketPullRequestResponse::pullRequestUrl)
                                .orElse(replayCase.getPullRequestUrl() == null
                                        ? ""
                                        : replayCase.getPullRequestUrl()),
                        pr.map(BitbucketPullRequestResponse::sourceBranch)
                                .orElse(""),
                        pr.map(BitbucketPullRequestResponse::targetBranch)
                                .orElse(""),
                        pr.map(value -> value.created()
                                ? "CREATED"
                                : "NOT_CREATED").orElse("NOT_CREATED")
                ),
                GUARDRAILS,
                properties.getRealActions().isEnabled()
                        ? List.of()
                        : List.of("REAL_ACTIONS_DISABLED"),
                List.of(),
                List.of("Use preview endpoints before enabling real actions.")
        );
    }

    private Optional<EvidenceEntity> latest(UUID caseId, EvidenceType type) {
        return evidenceRepository.findByCaseIdAndEvidenceType(caseId, type)
                .stream()
                .max(Comparator.comparing(EvidenceEntity::getCreatedAt));
    }

    private <T> Optional<T> read(EvidenceEntity entity, Class<T> type) {
        try {
            return Optional.of(objectMapper.readValue(
                    entity.getContentText(),
                    type
            ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
