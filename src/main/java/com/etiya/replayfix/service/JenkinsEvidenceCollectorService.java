package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.integration.JenkinsClient;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
import com.etiya.replayfix.model.JenkinsCaseEvidence;
import com.etiya.replayfix.model.RepositoryResolutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class JenkinsEvidenceCollectorService {

    private static final Logger log = LoggerFactory.getLogger(JenkinsEvidenceCollectorService.class);

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

        // Diagnostic logging (NO CREDENTIALS)
        log.info("JENKINS_REPOSITORY_RESOLVED: caseId={}, projectKey={}, repositorySlug={}, primaryResolved={}",
                caseId, resolution.projectKey(), repositorySlug, 
                (repositorySlug != null && !repositorySlug.isBlank()));

        if (repositorySlug == null
                || repositorySlug.isBlank()) {
            log.error("JENKINS_PRIMARY_REPOSITORY_MISSING: caseId={}, projectKey={}", 
                    caseId, resolution.projectKey());
            throw new IllegalStateException(
                    "Primary repository was not resolved."
            );
        }
        
        if (resolution.projectKey() == null || resolution.projectKey().isBlank()) {
            log.error("JENKINS_PROJECT_KEY_MISSING: caseId={}, repositorySlug={}", 
                    caseId, repositorySlug);
            throw new IllegalStateException(
                    "Project key was not resolved."
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

        // Check if jobUrl is a boolean value (common configuration error)
        if (jobUrl.equalsIgnoreCase("true") || jobUrl.equalsIgnoreCase("false")) {
            warnings.add(
                    "Jenkins "
                            + jobType
                            + " job URL is set to boolean value '"
                            + jobUrl
                            + "' - skipping"
            );
            log.warn("JENKINS_{}_JOB_DISABLED: Boolean value '{}' used instead of URL", 
                    jobType.toUpperCase(), jobUrl);
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
            // Try canonical RepositoryResolutionResult first
            return objectMapper.readValue(
                    evidence.getContentText(),
                    RepositoryResolutionResult.class
            );

        } catch (Exception exception) {
            // Backward-compatible parsing for legacy evidence
            try {
                return parseLegacyEvidence(evidence.getContentText());
            } catch (Exception legacyException) {
                throw new IllegalStateException(
                        "Cannot parse repository resolution. " +
                        "Canonical parse error: " + exception.getMessage() + 
                        "; Legacy parse error: " + legacyException.getMessage(),
                        exception
                );
            }
        }
    }

    private RepositoryResolutionResult parseLegacyEvidence(String contentText) throws Exception {
        // Parse as generic JSON node for backward compatibility
        var node = objectMapper.readTree(contentText);
        
        // Extract projectKey (try multiple field names)
        String projectKey = extractField(node, "projectKey", "bitbucketProjectKey");
        
        // Extract primaryRepositorySlug (try multiple field names)
        String primarySlug = extractField(node, 
                "primaryRepositorySlug", 
                "repositorySlug", 
                "slug", 
                "repoSlug",
                "primaryRepository");
        
        if (primarySlug == null || primarySlug.isBlank()) {
            throw new IllegalStateException("Primary repository slug not found in evidence");
        }
        
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalStateException("Project key not found in evidence");
        }
        
        // Build RepositoryResolutionResult with extracted fields
        return new RepositoryResolutionResult(
                projectKey,
                primarySlug,
                List.of(), // candidates
                List.of(), // unresolvedSignals
                "" // warning
        );
    }
    
    private String extractField(com.fasterxml.jackson.databind.JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                String value = node.get(fieldName).asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
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
