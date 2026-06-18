package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AiEvidenceBundle;
import com.etiya.replayfix.model.AiInputBundleRefreshResult;
import com.etiya.replayfix.model.IncidentVersionResolution;
import com.etiya.replayfix.model.JenkinsCaseEvidence;
import com.etiya.replayfix.model.JenkinsIncidentVersionValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiInputBundleRefreshService {

    private static final String OUTPUT_SOURCE =
            "replayfix-ai-bundle-builder";

    private static final String BUNDLE_VERSION =
            "replayfix-ai-bundle-v1";

    private final ReplayFixProperties properties;
    private final EvidenceService evidenceService;
    private final EvidenceSanitizer evidenceSanitizer;
    private final AiEvidenceBundleBuilder bundleBuilder;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AiInputBundleRefreshService(
            ReplayFixProperties properties,
            EvidenceService evidenceService,
            EvidenceSanitizer evidenceSanitizer,
            AiEvidenceBundleBuilder bundleBuilder,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.evidenceService = evidenceService;
        this.evidenceSanitizer = evidenceSanitizer;
        this.bundleBuilder = bundleBuilder;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public AiInputBundleRefreshResult refresh(
            UUID caseId
    ) {
        List<EvidenceEntity> evidenceList =
                evidenceService.list(caseId);

        EvidenceEntity jira =
                latestOptional(
                        evidenceList,
                        EvidenceType.JIRA_ISSUE,
                        null
                );

        EvidenceEntity lokiPlan =
                latestOptional(
                        evidenceList,
                        EvidenceType.LOKI_QUERY_PLAN,
                        null
                );

        EvidenceEntity lokiLog =
                latestOptional(
                        evidenceList,
                        EvidenceType.LOKI_LOG,
                        null
                );

        EvidenceEntity tempo =
                latestOptional(
                        evidenceList,
                        EvidenceType.TEMPO_TRACE,
                        null
                );

        EvidenceEntity repositoryResolution =
                latestOptional(
                        evidenceList,
                        EvidenceType.REPOSITORY_RESOLUTION,
                        null
                );

        EvidenceEntity incidentVersionEvidence =
                latestRequired(
                        evidenceList,
                        EvidenceType.INCIDENT_VERSION,
                        null
                );

        EvidenceEntity jenkinsContext =
                latestRequired(
                        evidenceList,
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-evidence-collector"
                );

        EvidenceEntity jenkinsValidation =
                latestOptional(
                        evidenceList,
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        "jenkins-incident-version-validator"
                );

        EvidenceEntity validatedSource =
                latestRequired(
                        evidenceList,
                        EvidenceType.SOURCE_CONTEXT,
                        "jenkins-validated-source-context"
                );

        EvidenceEntity previousSource =
                latestSourceBeforeValidated(
                        evidenceList,
                        validatedSource
                );

        EvidenceEntity deterministicRootCause =
                latestOptional(
                        evidenceList,
                        EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                        null
                );

        IncidentVersionResolution incidentVersion =
                parse(
                        incidentVersionEvidence,
                        IncidentVersionResolution.class
                );

        JenkinsCaseEvidence jenkinsEvidence =
                parse(
                        jenkinsContext,
                        JenkinsCaseEvidence.class
                );

        JenkinsIncidentVersionValidation validation =
                jenkinsValidation == null
                        ? null
                        : parse(
                                jenkinsValidation,
                                JenkinsIncidentVersionValidation.class
                        );

        List<String> warnings =
                new ArrayList<>();

        Map<String, String> sections =
                new LinkedHashMap<>();

        putSection(
                sections,
                "jira",
                jira,
                properties.getPolicy()
                        .getAiBundleMaxJiraChars(),
                warnings
        );

        putSection(
                sections,
                "lokiQueryPlan",
                lokiPlan,
                15000,
                warnings
        );

        putSection(
                sections,
                "lokiLogs",
                lokiLog,
                properties.getPolicy()
                        .getAiBundleMaxLokiChars(),
                warnings
        );

        putSection(
                sections,
                "tempoTrace",
                tempo,
                properties.getPolicy()
                        .getAiBundleMaxTempoChars(),
                warnings
        );

        putSection(
                sections,
                "repositoryResolution",
                repositoryResolution,
                15000,
                warnings
        );

        putSection(
                sections,
                "incidentVersion",
                incidentVersionEvidence,
                10000,
                warnings
        );

        putSection(
                sections,
                "jenkinsContext",
                jenkinsContext,
                20000,
                warnings
        );

        putSection(
                sections,
                "jenkinsValidation",
                jenkinsValidation,
                15000,
                warnings
        );

        putSection(
                sections,
                "validatedSourceContext",
                validatedSource,
                properties.getPolicy()
                        .getAiBundleMaxSourceChars(),
                warnings
        );

        putSection(
                sections,
                "previousSourceContext",
                previousSource,
                20000,
                warnings
        );

        putSection(
                sections,
                "deterministicRootCause",
                deterministicRootCause,
                15000,
                warnings
        );

        String jenkinsCommitSha =
                validation != null
                        && validation.jenkinsCommitSha() != null
                        && !validation.jenkinsCommitSha().isBlank()
                        ? validation.jenkinsCommitSha()
                        : jenkinsEvidence.build() != null
                                ? jenkinsEvidence.build().commitSha()
                                : incidentVersion.resolvedCommitSha();
        
        AiEvidenceBundle bundle =
                bundleBuilder.buildValidatedBundle(
                        caseId,
                        BUNDLE_VERSION,
                        sections,
                        incidentVersion.resolvedCommitSha(),
                        jenkinsCommitSha,
                        warnings
                );

        String serialized =
                serializeAndLimit(
                        bundle,
                        warnings
                );

        saveBundle(
                caseId,
                serialized
        );

        Map<String, Integer> sectionLengths =
                new LinkedHashMap<>();

        sections.forEach(
                (name, value) ->
                        sectionLengths.put(
                                name,
                                value == null
                                        ? 0
                                        : value.length()
                        )
        );

        List<String> included =
                sections.entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getValue() != null
                                        && !entry.getValue().isBlank()
                        )
                        .map(Map.Entry::getKey)
                        .toList();

        boolean mismatch =
                validation != null
                        && "MISMATCH".equals(
                                validation.status()
                        );

        AiInputBundleRefreshResult result =
                new AiInputBundleRefreshResult(
                        caseId,
                        BUNDLE_VERSION,
                        validatedSource.getSource(),
                        jenkinsCommitSha,
                        incidentVersion.resolvedCommitSha(),
                        mismatch,
                        serialized.length(),
                        sectionLengths,
                        included,
                        warnings,
                        bundle
                );

        auditService.record(
                caseId,
                "AI_INPUT_BUNDLE_REFRESHED",
                "replayfix-platform",
                "Bundle refreshed with Jenkins source context. "
                        + "jenkinsCommit="
                        + jenkinsCommitSha
                        + ", incidentCommit="
                        + incidentVersion.resolvedCommitSha()
                        + ", length="
                        + serialized.length()
        );

        return result;
    }

    private void putSection(
            Map<String, String> sections,
            String name,
            EvidenceEntity evidence,
            int maxChars,
            List<String> warnings
    ) {
        if (evidence == null) {
            sections.put(name, "");
            return;
        }

        String sanitized =
                sanitize(
                        evidence.getContentText()
                );

        String limited =
                truncate(
                        sanitized,
                        maxChars
                );

        if (sanitized.length()
                > limited.length()) {
            warnings.add(
                    "Section was truncated: "
                            + name
                            + ", originalLength="
                            + sanitized.length()
                            + ", includedLength="
                            + limited.length()
            );
        }

        sections.put(
                name,
                limited
        );
    }

    private String sanitize(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        return evidenceSanitizer.sanitize(value);
    }

    private String serializeAndLimit(
            AiEvidenceBundle bundle,
            List<String> warnings
    ) {
        try {
            String serialized =
                    objectMapper.writeValueAsString(
                            bundle
                    );

            int maxChars =
                    properties.getPolicy()
                            .getAiBundleMaxChars();

            if (serialized.length()
                    <= maxChars) {
                return serialized;
            }

            warnings.add(
                    "Final bundle exceeded maximum size. "
                            + "originalLength="
                            + serialized.length()
                            + ", maxLength="
                            + maxChars
            );

            throw new IllegalStateException(
                    "AI input bundle exceeds configured maximum size: "
                            + serialized.length()
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot serialize AI input bundle.",
                    exception
            );
        }
    }

    private void saveBundle(
            UUID caseId,
            String serialized
    ) {
        evidenceService.save(
                caseId,
                EvidenceType.AI_INPUT_BUNDLE,
                OUTPUT_SOURCE,
                serialized,
                true
        );
    }

    private EvidenceEntity latestRequired(
            List<EvidenceEntity> evidence,
            EvidenceType type,
            String source
    ) {
        EvidenceEntity result =
                latestOptional(
                        evidence,
                        type,
                        source
                );

        if (result == null) {
            throw new IllegalStateException(
                    "Required evidence not found. type="
                            + type
                            + ", source="
                            + source
            );
        }

        return result;
    }

    private EvidenceEntity latestOptional(
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
                .orElse(null);
    }

    private EvidenceEntity latestSourceBeforeValidated(
            List<EvidenceEntity> evidence,
            EvidenceEntity validatedSource
    ) {
        return evidence.stream()
                .filter(item ->
                        item.getEvidenceType()
                                == EvidenceType.SOURCE_CONTEXT
                )
                .filter(item ->
                        !"jenkins-validated-source-context"
                                .equals(
                                        item.getSource()
                                )
                )
                .filter(item ->
                        validatedSource.getCreatedAt() == null
                                || item.getCreatedAt() == null
                                || !item.getCreatedAt()
                                        .isAfter(
                                                validatedSource
                                                        .getCreatedAt()
                                        )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElse(null);
    }

    private <T> T parse(
            EvidenceEntity evidence,
            Class<T> type
    ) {
        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    type
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

    private String truncate(
            String value,
            int maxChars
    ) {
        if (value == null) {
            return "";
        }

        if (maxChars <= 0
                || value.length()
                        <= maxChars) {
            return value;
        }

        return value.substring(
                0,
                maxChars
        );
    }
}
