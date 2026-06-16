package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.model.AiEvidenceBundle;
import com.etiya.replayfix.model.DeterministicRootCauseRefreshResult;
import com.etiya.replayfix.model.DeterministicRootCauseReport;
import com.etiya.replayfix.model.JenkinsIncidentVersionValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DeterministicRootCauseRefreshService {

    private static final String BUNDLE_SOURCE =
            "jenkins-validated-ai-bundle";

    private static final String REPORT_SOURCE =
            "deterministic-root-cause-jenkins-validated";

    private static final String VALIDATION_SOURCE =
            "jenkins-incident-version-validator";

    private final EvidenceService evidenceService;
    private final DeterministicRootCauseReportBuilder reportBuilder;
    private final RootCauseReportComparisonService comparisonService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public DeterministicRootCauseRefreshService(
            EvidenceService evidenceService,
            DeterministicRootCauseReportBuilder reportBuilder,
            RootCauseReportComparisonService comparisonService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.reportBuilder = reportBuilder;
        this.comparisonService = comparisonService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public DeterministicRootCauseRefreshResult refresh(
            UUID caseId
    ) {
        List<EvidenceEntity> evidence =
                evidenceService.list(caseId);

        EvidenceEntity bundleEvidence =
                latestRequired(
                        evidence,
                        EvidenceType.AI_INPUT_BUNDLE,
                        BUNDLE_SOURCE
                );

        AiEvidenceBundle bundle =
                parse(
                        bundleEvidence,
                        AiEvidenceBundle.class
                );

        EvidenceEntity validationEvidence =
                latestOptional(
                        evidence,
                        EvidenceType.JENKINS_BUILD_CONTEXT,
                        VALIDATION_SOURCE
                );

        JenkinsIncidentVersionValidation validation =
                validationEvidence == null
                        ? null
                        : parse(
                                validationEvidence,
                                JenkinsIncidentVersionValidation.class
                        );

        EvidenceEntity previousReportEvidence =
                latestPreviousRootCause(
                        evidence
                );

        DeterministicRootCauseReport previousReport =
                previousReportEvidence == null
                        ? null
                        : parse(
                                previousReportEvidence,
                                DeterministicRootCauseReport.class
                        );

        DeterministicRootCauseReport refreshedReport =
                reportBuilder.buildFromBundle(
                        bundle
                );

        validateReport(
                refreshedReport
        );

        List<String> changes =
                comparisonService.compare(
                        previousReport,
                        refreshedReport
                );

        List<String> warnings =
                new ArrayList<>();

        if (validation == null) {
            warnings.add(
                    "Jenkins incident version validation evidence was not found."
            );
        }

        if (validation != null
                && "MISMATCH".equals(
                        validation.status()
                )) {
            warnings.add(
                    "The original incident commit and Jenkins commit differ. "
                            + "The refreshed report uses Jenkins-validated source context."
            );
        }

        saveReport(
                caseId,
                refreshedReport
        );

        DeterministicRootCauseRefreshResult result =
                new DeterministicRootCauseRefreshResult(
                        caseId,
                        BUNDLE_SOURCE,
                        REPORT_SOURCE,
                        previousReportEvidence == null
                                ? ""
                                : previousReportEvidence.getSource(),
                        validation == null
                                ? ""
                                : validation.jenkinsCommitSha(),
                        validation == null
                                ? ""
                                : validation.incidentVersionCommitSha(),
                        validation != null
                                && "MISMATCH".equals(
                                        validation.status()
                                ),
                        refreshedReport,
                        previousReport,
                        changes,
                        warnings
                );

        auditService.record(
                caseId,
                "DETERMINISTIC_ROOT_CAUSE_REFRESHED",
                "replayfix-platform",
                "Deterministic root-cause refreshed from "
                        + BUNDLE_SOURCE
                        + ". reportSource="
                        + REPORT_SOURCE
                        + ", changeCount="
                        + changes.size()
        );

        return result;
    }

    private void validateReport(
            DeterministicRootCauseReport report
    ) {
        if (report == null) {
            throw new IllegalStateException(
                    "Deterministic root-cause builder returned null."
            );
        }

        if (report.probableCause() == null
                || report.probableCause()
                        .isBlank()) {
            throw new IllegalStateException(
                    "Probable root cause is empty."
            );
        }
    }

    private void saveReport(
            UUID caseId,
            DeterministicRootCauseReport report
    ) {
        try {
            evidenceService.save(
                    caseId,
                    EvidenceType.AI_ROOT_CAUSE,
                    REPORT_SOURCE,
                    objectMapper.writeValueAsString(
                            report
                    ),
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save refreshed deterministic root-cause report.",
                    exception
            );
        }
    }

    private EvidenceEntity latestPreviousRootCause(
            List<EvidenceEntity> evidence
    ) {
        return evidence.stream()
                .filter(item ->
                        item.getEvidenceType()
                                == EvidenceType.AI_ROOT_CAUSE
                )
                .filter(item ->
                        !REPORT_SOURCE.equals(
                                item.getSource()
                        )
                )
                .reduce(
                        (first, second) ->
                                second
                )
                .orElse(null);
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
}
