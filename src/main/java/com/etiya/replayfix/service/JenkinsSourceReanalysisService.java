package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.IncidentSignals;
import com.etiya.replayfix.model.IncidentTimeline;
import com.etiya.replayfix.model.IncidentVersionResolution;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import com.etiya.replayfix.model.JenkinsIncidentVersionValidation;
import com.etiya.replayfix.model.JenkinsSourceReanalysisResult;
import com.etiya.replayfix.model.SourceCheckoutResult;
import com.etiya.replayfix.model.SourceContextResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class JenkinsSourceReanalysisService {

    private static final String SOURCE =
            "jenkins-validated-source-context";

    private final ReplayCaseService replayCaseService;
    private final EvidenceService evidenceService;
    private final GitWorkspaceService gitWorkspaceService;
    private final SourceCodeContextService sourceCodeContextService;
    private final JiraAdfTextExtractor jiraAdfTextExtractor;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public JenkinsSourceReanalysisService(
            ReplayCaseService replayCaseService,
            EvidenceService evidenceService,
            GitWorkspaceService gitWorkspaceService,
            SourceCodeContextService sourceCodeContextService,
            JiraAdfTextExtractor jiraAdfTextExtractor,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.replayCaseService = replayCaseService;
        this.evidenceService = evidenceService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.sourceCodeContextService = sourceCodeContextService;
        this.jiraAdfTextExtractor = jiraAdfTextExtractor;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public JenkinsSourceReanalysisResult reanalyze(
            UUID caseId
    ) {
        var replayCase =
                replayCaseService.get(caseId);

        JenkinsIncidentVersionValidation validation =
                readLatest(
                        caseId,
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-incident-version-validator",
                        JenkinsIncidentVersionValidation.class
                );

        if (!"MISMATCH".equals(
                validation.status()
        )) {
            throw new IllegalStateException(
                    "Jenkins source reanalysis is allowed only "
                            + "when validation status is MISMATCH. "
                            + "Current status: "
                            + validation.status()
            );
        }

        if (validation.jenkinsCommitSha() == null
                || validation.jenkinsCommitSha()
                        .isBlank()) {
            throw new IllegalStateException(
                    "Jenkins commit SHA is empty."
            );
        }

        SourceCheckoutResult checkout =
                readLatest(
                        caseId,
                        EvidenceType.SOURCE_CHECKOUT,
                        null,
                        SourceCheckoutResult.class
                );

        IncidentVersionResolution incidentVersion =
                readLatest(
                        caseId,
                        EvidenceType.INCIDENT_VERSION,
                        null,
                        IncidentVersionResolution.class
                );

        JiraIssue jiraIssue =
                readLatest(
                        caseId,
                        EvidenceType.JIRA_ISSUE,
                        null,
                        JiraIssue.class
                );

        String plainDescription =
                resolvePlainDescription(jiraIssue);

        IncidentSignals signals =
                resolveIncidentSignals(caseId);

        IncidentTimeline timeline =
                resolveIncidentTimeline(caseId);

        var commitCheckout =
                gitWorkspaceService
                        .checkoutReadOnlyCommit(
                                checkout.workspace(),
                                validation.jenkinsCommitSha()
                        );

        SourceContextResult sourceContext =
                sourceCodeContextService
                        .collectFromRoot(
                                Path.of(
                                        commitCheckout.workspace()
                                ),
                                validation.repositorySlug(),
                                jiraIssue,
                                plainDescription,
                                signals,
                                timeline
                        );

        List<String> warnings =
                new ArrayList<>();

        warnings.add(
                "INCIDENT_VERSION evidence was not overwritten."
        );

        warnings.add(
                "Source context was regenerated using "
                        + "the Jenkins commit selected by "
                        + "an explicit user request."
        );

        JenkinsSourceReanalysisResult result =
                new JenkinsSourceReanalysisResult(
                        caseId,
                        validation.repositorySlug(),
                        incidentVersion.resolvedCommitSha(),
                        commitCheckout.commitSha(),
                        commitCheckout.workspace(),
                        commitCheckout.fetched(),
                        sourceContext,
                        warnings
                );

        saveResult(
                caseId,
                result
        );

        auditService.record(
                caseId,
                "JENKINS_SOURCE_REANALYSIS",
                "replayfix-platform",
                "Source context regenerated. previousCommit="
                        + incidentVersion.resolvedCommitSha()
                        + ", jenkinsCommit="
                        + commitCheckout.commitSha()
        );

        return result;
    }

    private void saveResult(
            UUID caseId,
            JenkinsSourceReanalysisResult result
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.SOURCE_CONTEXT,
                    SOURCE,
                    objectMapper.writeValueAsString(
                            result
                    ),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Jenkins source reanalysis.",
                    exception
            );
        }
    }

    private <T> T readLatest(
            UUID caseId,
            EvidenceType type,
            String source,
            Class<T> targetType
    ) {
        EvidenceEntity evidence =
                evidenceService.list(caseId)
                        .stream()
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
                                (first, second) -> second
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Evidence not found. type="
                                                + type
                                                + ", source="
                                                + source
                                )
                        );

        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    targetType
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse evidence. type="
                            + type
                            + ", source="
                            + source,
                    exception
            );
        }
    }

    private String resolvePlainDescription(
            JiraIssue jiraIssue
    ) {
        return jiraAdfTextExtractor.extract(
                jiraIssue.description()
        );
    }

    private IncidentSignals resolveIncidentSignals(
            UUID caseId
    ) {
        return readLatest(
                caseId,
                EvidenceType.LOKI_CORRELATION_SIGNALS,
                null,
                IncidentSignals.class
        );
    }

    private IncidentTimeline resolveIncidentTimeline(
            UUID caseId
    ) {
        return readLatest(
                caseId,
                EvidenceType.INCIDENT_TIMELINE,
                null,
                IncidentTimeline.class
        );
    }
}
