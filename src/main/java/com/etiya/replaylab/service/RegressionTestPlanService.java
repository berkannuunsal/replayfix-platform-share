package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.DeterministicRootCauseReport;
import com.etiya.replaylab.model.IncidentVersionResolution;
import com.etiya.replaylab.model.JenkinsIncidentVersionValidation;
import com.etiya.replaylab.model.RegressionTestPlan;
import com.etiya.replaylab.model.RegressionTestPlanResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RegressionTestPlanService {

    private static final String PLAN_SOURCE =
            "regression-test-plan";

    private final EvidenceService evidenceService;
    private final RegressionTestPlanBuilder planBuilder;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public RegressionTestPlanService(
            EvidenceService evidenceService,
            RegressionTestPlanBuilder planBuilder,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.planBuilder = planBuilder;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public RegressionTestPlanResult generate(
            UUID caseId
    ) {
        List<EvidenceEntity> evidence =
                evidenceService.list(caseId);

        EvidenceEntity rootCauseEvidence =
                latestRequired(
                        evidence,
                        EvidenceType.AI_ROOT_CAUSE,
                        "deterministic-root-cause-jenkins-validated"
                );

        EvidenceEntity jiraEvidence =
                latestRequired(
                        evidence,
                        EvidenceType.JIRA_ISSUE,
                        null
                );

        EvidenceEntity sourceEvidence =
                latestRequired(
                        evidence,
                        EvidenceType.SOURCE_CONTEXT,
                        "jenkins-validated-source-context"
                );

        EvidenceEntity validationEvidence =
                latestRequired(
                        evidence,
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-incident-version-validator"
                );

        EvidenceEntity incidentVersionEvidence =
                latestRequired(
                        evidence,
                        EvidenceType.INCIDENT_VERSION,
                        null
                );

        DeterministicRootCauseReport rootCause =
                parse(
                        rootCauseEvidence,
                        DeterministicRootCauseReport.class
                );

        JsonNode jira =
                parseJson(jiraEvidence);

        JsonNode sourceContext =
                parseJson(sourceEvidence);

        JsonNode jenkins =
                parseJson(validationEvidence);

        JenkinsIncidentVersionValidation validation =
                parse(
                        validationEvidence,
                        JenkinsIncidentVersionValidation.class
                );

        IncidentVersionResolution incidentVersion =
                parse(
                        incidentVersionEvidence,
                        IncidentVersionResolution.class
                );

        String selectedCommit =
                validation.jenkinsCommitSha() == null
                        || validation.jenkinsCommitSha()
                                .isBlank()
                        ? incidentVersion.resolvedCommitSha()
                        : validation.jenkinsCommitSha();

        RegressionTestPlan plan =
                planBuilder.build(
                        caseId,
                        validation.repositorySlug(),
                        selectedCommit,
                        rootCause,
                        jira,
                        sourceContext,
                        jenkins
                );

        validatePlan(plan);

        savePlan(
                caseId,
                plan
        );

        List<String> warnings =
                new ArrayList<>(
                        plan.warnings()
                );

        warnings.add(
                "No source file was written."
        );

        warnings.add(
                "No test command was executed."
        );

        RegressionTestPlanResult result =
                new RegressionTestPlanResult(
                        caseId,
                        EvidenceType.GENERATED_TEST.name(),
                        PLAN_SOURCE,
                        plan,
                        false,
                        false,
                        warnings
                );

        auditService.record(
                caseId,
                "REGRESSION_TEST_PLAN_CREATED",
                "replaylab-platform",
                "Regression test plan created. "
                        + "repository="
                        + plan.repositorySlug()
                        + ", commit="
                        + plan.sourceCommitSha()
                        + ", proposedFile="
                        + plan.proposedFilePath()
                        + ", confidence="
                        + plan.confidence()
        );

        return result;
    }

    private void validatePlan(
            RegressionTestPlan plan
    ) {
        if (plan == null) {
            throw new IllegalStateException(
                    "Regression test plan is null."
            );
        }

        if (plan.proposedFilePath() == null
                || plan.proposedFilePath()
                        .isBlank()) {
            throw new IllegalStateException(
                    "Proposed test file path is empty."
            );
        }

        String normalizedPath =
                plan.proposedFilePath()
                        .replace(
                                '\\',
                                '/'
                        );

        if (!normalizedPath.startsWith(
                "src/test/"
        )) {
            throw new IllegalStateException(
                    "Proposed test file must be under src/test: "
                            + normalizedPath
            );
        }

        if (normalizedPath.contains("../")
                || normalizedPath.startsWith("/")) {
            throw new IllegalStateException(
                    "Proposed test path escapes workspace: "
                            + normalizedPath
            );
        }

        if (plan.writeAuthorized()) {
            throw new IllegalStateException(
                    "Plan-only result cannot authorize file writing."
            );
        }

        if (plan.executionAuthorized()) {
            throw new IllegalStateException(
                    "Plan-only result cannot authorize test execution."
            );
        }

        if (!plan.humanApprovalRequired()) {
            throw new IllegalStateException(
                    "Human approval must be required."
            );
        }
    }

    private void savePlan(
            UUID caseId,
            RegressionTestPlan plan
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.GENERATED_TEST,
                    PLAN_SOURCE,
                    objectMapper.writeValueAsString(
                            plan
                    ),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save regression test plan.",
                    exception
            );
        }
    }

    private EvidenceEntity latestRequired(
            List<EvidenceEntity> evidence,
            EvidenceType type,
            String source
    ) {
        return evidence.stream()
                .filter(item ->
                        item.getEvidenceType()
                                == type
                )
                .filter(item ->
                        source == null
                                || source.equals(
                                        item.getSource()
                                )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Required evidence not found. type="
                                        + type
                                        + ", source="
                                        + source
                        )
                );
    }

    private <T> T parse(
            EvidenceEntity evidence,
            Class<T> targetType
    ) {
        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    targetType
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse evidence. type="
                            + evidence.getEvidenceType()
                            + ", source="
                            + evidence.getSource(),
                    exception
            );
        }
    }

    private JsonNode parseJson(
            EvidenceEntity evidence
    ) {
        try {
            return objectMapper.readTree(
                    evidence.getContentText()
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse JSON evidence. type="
                            + evidence.getEvidenceType()
                            + ", source="
                            + evidence.getSource(),
                    exception
            );
        }
    }
}
