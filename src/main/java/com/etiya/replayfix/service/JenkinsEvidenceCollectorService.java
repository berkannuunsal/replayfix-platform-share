package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.integration.JenkinsClient;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
import com.etiya.replayfix.model.JenkinsCaseEvidence;
import com.etiya.replayfix.model.RepositoryResolutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class JenkinsEvidenceCollectorService {

    private final ReplayFixProperties properties;
    private final JenkinsClient jenkinsClient;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public JenkinsEvidenceCollectorService(
            ReplayFixProperties properties,
            JenkinsClient jenkinsClient,
            EvidenceService evidenceService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.jenkinsClient = jenkinsClient;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    public JenkinsCaseEvidence collect(
            UUID caseId
    ) {
        var jenkinsConfig = properties.getIntegrations()
                .getJenkins();

        if (!jenkinsConfig.isEnabled()) {
            throw new IllegalStateException(
                    "Jenkins integration is disabled."
            );
        }

        RepositoryResolutionResult resolution =
                readRepositoryResolution(caseId);

        String repositorySlug =
                resolution.primaryRepositorySlug();

        if (repositorySlug == null
                || repositorySlug.isBlank()) {
            throw new IllegalStateException(
                    "Primary repository was not resolved."
            );
        }

        String applicationKey = resolveApplicationKey(
                repositorySlug,
                jenkinsConfig.getApplications()
        );

        var application = jenkinsConfig.getApplications()
                .get(applicationKey);

        List<String> warnings = new ArrayList<>();

        JenkinsBuildSnapshot buildSnapshot = readSnapshot(
                application.getBuildJobUrl(),
                "build",
                warnings
        );

        JenkinsBuildSnapshot imageSnapshot = readSnapshot(
                application.getImageJobUrl(),
                "image",
                warnings
        );

        JenkinsCaseEvidence evidence =
                new JenkinsCaseEvidence(
                        caseId,
                        applicationKey,
                        repositorySlug,
                        buildSnapshot,
                        imageSnapshot,
                        warnings
                );

        saveEvidence(caseId, evidence);

        return evidence;
    }

    private JenkinsBuildSnapshot readSnapshot(
            String jobUrl,
            String jobType,
            List<String> warnings
    ) {
        if (jobUrl == null || jobUrl.isBlank()) {
            warnings.add(
                    "Jenkins "
                            + jobType
                            + " job URL is not configured."
            );
            return null;
        }

        try {
            return jenkinsClient
                    .readLastSuccessfulBuild(jobUrl);

        } catch (Exception exception) {
            warnings.add(
                    "Cannot read Jenkins "
                            + jobType
                            + " job: "
                            + rootCauseMessage(exception)
            );
            return null;
        }
    }

    private String resolveApplicationKey(
            String repositorySlug,
            Map<String, ReplayFixProperties.JenkinsApplication> applications
    ) {
        String normalizedRepository =
                normalize(repositorySlug);

        for (var entry : applications.entrySet()) {
            if (normalize(entry.getKey())
                    .equals(normalizedRepository)) {
                return entry.getKey();
            }

            List<String> aliases = entry.getValue()
                    .getRepositoryAliases();

            if (aliases == null) {
                continue;
            }

            boolean matched = aliases.stream()
                    .map(this::normalize)
                    .anyMatch(
                            normalizedRepository::equals
                    );

            if (matched) {
                return entry.getKey();
            }
        }

        throw new IllegalStateException(
                "No Jenkins application mapping found "
                        + "for repository: "
                        + repositorySlug
        );
    }

    private RepositoryResolutionResult
    readRepositoryResolution(
            UUID caseId
    ) {
        EvidenceEntity evidence =
                evidenceService.list(caseId)
                        .stream()
                        .filter(item ->
                                item.getEvidenceType()
                                        == EvidenceType
                                        .REPOSITORY_RESOLUTION
                        )
                        .reduce(
                                (first, second) -> second
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Repository resolution "
                                                + "evidence not found."
                                )
                        );

        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    RepositoryResolutionResult.class
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse repository resolution.",
                    exception
            );
        }
    }

    private void saveEvidence(
            UUID caseId,
            JenkinsCaseEvidence evidence
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.JENKINS_BUILD_CONTEXT,
                    "jenkins-evidence-collector",
                    objectMapper.writeValueAsString(
                            evidence
                    ),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Jenkins evidence.",
                    exception
            );
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
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
}
