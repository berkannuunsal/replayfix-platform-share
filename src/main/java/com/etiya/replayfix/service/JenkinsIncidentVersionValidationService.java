package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.integration.JenkinsClient;
import com.etiya.replayfix.model.IncidentVersionResolution;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
import com.etiya.replayfix.model.JenkinsCaseEvidence;
import com.etiya.replayfix.model.JenkinsIncidentVersionValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class JenkinsIncidentVersionValidationService {

    private static final String VALIDATION_SOURCE =
            "jenkins-incident-version-validator";

    private final ReplayCaseService replayCaseService;
    private final ReplayFixProperties properties;
    private final JenkinsClient jenkinsClient;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public JenkinsIncidentVersionValidationService(
            ReplayCaseService replayCaseService,
            ReplayFixProperties properties,
            JenkinsClient jenkinsClient,
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.replayCaseService = replayCaseService;
        this.properties = properties;
        this.jenkinsClient = jenkinsClient;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    public JenkinsIncidentVersionValidation validate(
            UUID caseId
    ) {
        var replayCase =
                replayCaseService.get(caseId);

        Instant incidentTime =
                replayCase.getIncidentTime();

        if (incidentTime == null) {
            throw new IllegalStateException(
                    "Case incident time is empty."
            );
        }

        IncidentVersionResolution incidentVersion =
                readLatestEvidence(
                        caseId,
                        EvidenceType.INCIDENT_VERSION,
                        null,
                        IncidentVersionResolution.class
                );

        JenkinsCaseEvidence jenkinsContext =
                readLatestEvidence(
                        caseId,
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-evidence-collector",
                        JenkinsCaseEvidence.class
                );

        var application =
                properties.getIntegrations()
                        .getJenkins()
                        .getApplications()
                        .get(jenkinsContext.applicationKey());

        if (application == null) {
            throw new IllegalStateException(
                    "Jenkins application configuration was not found: "
                            + jenkinsContext.applicationKey()
            );
        }

        List<String> warnings =
                new ArrayList<>();

        JenkinsBuildSnapshot buildAtIncident =
                readAtIncident(
                        application.getBuildJobUrl(),
                        incidentTime,
                        "build",
                        warnings
                );

        JenkinsBuildSnapshot imageAtIncident =
                readAtIncident(
                        application.getImageJobUrl(),
                        incidentTime,
                        "image",
                        warnings
                );

        String jenkinsCommit =
                firstNonBlank(
                        buildAtIncident == null
                                ? ""
                                : buildAtIncident.commitSha(),
                        imageAtIncident == null
                                ? ""
                                : imageAtIncident.commitSha()
                );

        if (buildAtIncident != null
                && imageAtIncident != null
                && hasText(buildAtIncident.commitSha())
                && hasText(imageAtIncident.commitSha())
                && !sameCommit(
                        buildAtIncident.commitSha(),
                        imageAtIncident.commitSha()
                )) {
            warnings.add(
                    "Build and image jobs resolved different commit SHA values."
            );
        }

        String incidentCommit =
                incidentVersion.resolvedCommitSha();

        MatchResult match =
                compareCommits(
                        incidentCommit,
                        jenkinsCommit
                );

        JenkinsIncidentVersionValidation validation =
                new JenkinsIncidentVersionValidation(
                        caseId,
                        jenkinsContext.applicationKey(),
                        jenkinsContext.repositorySlug(),
                        incidentTime,
                        buildAtIncident,
                        imageAtIncident,
                        incidentCommit,
                        jenkinsCommit,
                        match.status(),
                        match.exact(),
                        warnings
                );

        saveValidation(caseId, validation);

        return validation;
    }

    private JenkinsBuildSnapshot readAtIncident(
            String jobUrl,
            Instant incidentTime,
            String jobType,
            List<String> warnings
    ) {
        if (!hasText(jobUrl)) {
            warnings.add(
                    "Jenkins " + jobType
                            + " job URL is not configured."
            );
            return null;
        }

        try {
            return jenkinsClient.readBuildAtOrBefore(
                    jobUrl,
                    incidentTime
            );
        } catch (Exception exception) {
            warnings.add(
                    "Cannot resolve Jenkins "
                            + jobType
                            + " build at incident time: "
                            + rootCauseMessage(exception)
            );
            return null;
        }
    }

    private MatchResult compareCommits(
            String incidentCommit,
            String jenkinsCommit
    ) {
        if (!hasText(incidentCommit)) {
            return new MatchResult(
                    "NO_INCIDENT_COMMIT",
                    false
            );
        }

        if (!hasText(jenkinsCommit)) {
            return new MatchResult(
                    "NO_JENKINS_COMMIT",
                    false
            );
        }

        String left =
                incidentCommit.trim().toLowerCase();

        String right =
                jenkinsCommit.trim().toLowerCase();

        if (left.equals(right)) {
            return new MatchResult(
                    "EXACT_MATCH",
                    true
            );
        }

        if (left.startsWith(right)
                || right.startsWith(left)) {
            return new MatchResult(
                    "PREFIX_MATCH",
                    true
            );
        }

        return new MatchResult(
                "MISMATCH",
                false
        );
    }

    private boolean sameCommit(
            String first,
            String second
    ) {
        return compareCommits(first, second).exact();
    }

    private <T> T readLatestEvidence(
            UUID caseId,
            EvidenceType type,
            String requiredSource,
            Class<T> targetType
    ) {
        EvidenceEntity evidence =
                evidenceService.list(caseId)
                        .stream()
                        .filter(item ->
                                item.getEvidenceType() == type
                        )
                        .filter(item ->
                                requiredSource == null
                                        || requiredSource.equals(
                                                item.getSource()
                                        )
                        )
                        .reduce((first, second) -> second)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Evidence not found. type="
                                                + type
                                                + ", source="
                                                + requiredSource
                                )
                        );

        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    targetType
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse evidence. type=" + type,
                    exception
            );
        }
    }

    private void saveValidation(
            UUID caseId,
            JenkinsIncidentVersionValidation validation
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.JENKINS_BUILD_CONTEXT,
                    VALIDATION_SOURCE,
                    objectMapper.writeValueAsString(validation),
                    true
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Jenkins version validation.",
                    exception
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(
            String... values
    ) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String rootCauseMessage(
            Throwable throwable
    ) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass().getSimpleName()
                + ": "
                + root.getMessage();
    }

    private record MatchResult(
            String status,
            boolean exact
    ) {
    }
}
