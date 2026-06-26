package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabDoraImpactScoreboardResponse;
import com.etiya.replaylab.api.dto.ReplayLabFinalRemediationBriefResponse;
import com.etiya.replaylab.api.dto.ReplayLabRemediationReadinessResponse;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ReplayLabFinalRemediationBriefService {

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ReplayLabDoraImpactScoreboardService doraImpactService;
    private final ReplayLabRemediationReadinessService readinessService;

    public ReplayLabFinalRemediationBriefService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayLabDoraImpactScoreboardService doraImpactService,
            ReplayLabRemediationReadinessService readinessService
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.doraImpactService = doraImpactService;
        this.readinessService = readinessService;
    }

    @Transactional(readOnly = true)
    public ReplayLabFinalRemediationBriefResponse brief(UUID caseId) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId));
        List<EvidenceEntity> evidence = evidenceRepository.findByCaseId(caseId);
        return response(replayCase, evidence);
    }

    public ReplayLabFinalRemediationBriefResponse response(
            ReplayCaseEntity replayCase,
            List<EvidenceEntity> evidence
    ) {
        List<EvidenceEntity> safeEvidence = evidence == null ? List.of() : evidence;
        ReplayLabDoraImpactScoreboardResponse doraImpact = doraImpactService.response(replayCase);
        ReplayLabRemediationReadinessResponse readiness = readinessService.response(replayCase, safeEvidence);
        List<String> evidenceSummary = evidenceSummary(replayCase, safeEvidence);
        List<String> guardrails = List.of(
                "No PR merge",
                "No deployment",
                "No direct push to the target branch",
                "No credential or production payload disclosure"
        );
        List<String> nextActions = List.of(
                "Human reviewer inspects the targeted PR path.",
                "Jenkins validation is triggered only after approval.",
                "Jira receives the final ReplayLab remediation summary."
        );
        String markdown = markdown(replayCase, doraImpact, readiness, evidenceSummary);
        return new ReplayLabFinalRemediationBriefResponse(
                replayCase.getId(),
                safe(replayCase.getJiraKey()),
                safe(replayCase.getTargetKey()),
                firstNonBlank(replayCase.getEnvironment(), "test2"),
                doraImpact,
                readiness,
                evidenceSummary,
                guardrails,
                nextActions,
                markdown
        );
    }

    private List<String> evidenceSummary(
            ReplayCaseEntity replayCase,
            List<EvidenceEntity> evidence
    ) {
        List<String> values = new ArrayList<>();
        values.add("Jira case created");
        if (replayCase.isSynthetic() || hasAny(evidence, EvidenceType.JENKINS_BUILD_CONTEXT, EvidenceType.JENKINS_BUILD)) {
            values.add("Jenkins build context collected");
        }
        if (replayCase.isSynthetic() || hasAny(evidence, EvidenceType.INCIDENT_VERSION)) {
            values.add("Incident version validation available");
        }
        if (replayCase.isSynthetic() || !evidence.isEmpty()) {
            values.add("Evidence Snapshot available");
        }
        if (replayCase.isSynthetic() || hasAny(evidence, EvidenceType.ROVO_RCA, EvidenceType.AI_ROOT_CAUSE)) {
            values.add("Rovo RCA imported");
        }
        if (replayCase.isSynthetic() || hasPrPreflight(evidence)) {
            values.add("AGENTS preflight completed");
        }
        return List.copyOf(values);
    }

    private String markdown(
            ReplayCaseEntity replayCase,
            ReplayLabDoraImpactScoreboardResponse doraImpact,
            ReplayLabRemediationReadinessResponse readiness,
            List<String> evidenceSummary
    ) {
        int incidentVersionReduction = doraImpact.scoreboard().stream()
                .filter(item -> "Incident version validation".equals(item.activity()))
                .findFirst()
                .map(ReplayLabDoraImpactScoreboardResponse.ScoreboardItem::reductionPercent)
                .orElse(95);
        int preflightReduction = doraImpact.scoreboard().stream()
                .filter(item -> "PR rule preflight".equals(item.activity()))
                .findFirst()
                .map(ReplayLabDoraImpactScoreboardResponse.ScoreboardItem::reductionPercent)
                .orElse(93);
        return """
                ## ReplayLab Final Remediation Brief

                ### Defect

                - Defect: %s
                - ReplayLab case: %s
                - Target: %s
                - Environment: %s

                ### DORA Impact

                - Primary metric: %s
                - Estimated diagnosis reduction: %d%%
                - Estimated incident version validation reduction: %d%%
                - Estimated PR rule preflight reduction: %d%%

                ### Evidence

                %s

                ### Remediation Readiness

                - Score: %d/100
                - Verdict: %s

                ### Guardrails

                ReplayLab did not:

                - merge a PR
                - deploy to any environment
                - push directly to the target branch
                - disclose credentials or production payloads

                ### Next Actions

                - Human reviewer inspects the targeted PR path.
                - Jenkins validation is triggered only after approval.
                - Jira receives the final ReplayLab remediation summary.
                """.formatted(
                safe(replayCase.getJiraKey()),
                replayCase.getId(),
                safe(replayCase.getTargetKey()),
                firstNonBlank(replayCase.getEnvironment(), "test2"),
                doraImpact.primaryMetric(),
                doraImpact.overallEstimatedReductionPercent(),
                incidentVersionReduction,
                preflightReduction,
                bulletList(evidenceSummary),
                readiness.score(),
                readiness.verdict()
        );
    }

    private String bulletList(List<String> values) {
        return String.join("\n", values.stream()
                .map(value -> "- " + value)
                .toList());
    }

    private boolean hasPrPreflight(List<EvidenceEntity> evidence) {
        return evidence.stream()
                .filter(item -> item.getEvidenceType() == EvidenceType.PULL_REQUEST)
                .map(this::text)
                .anyMatch(value -> value.contains("PR_RULE_PREFLIGHT_STATUS")
                        || value.contains("reviewStatus")
                        || value.contains("rulesLoaded"));
    }

    private boolean hasAny(List<EvidenceEntity> evidence, EvidenceType... types) {
        for (EvidenceEntity item : evidence) {
            for (EvidenceType type : types) {
                if (item.getEvidenceType() == type) {
                    return true;
                }
            }
        }
        return false;
    }

    private String text(EvidenceEntity evidence) {
        if (evidence == null) {
            return "";
        }
        if (evidence.getContentText() != null && !evidence.getContentText().isBlank()) {
            return evidence.getContentText();
        }
        return evidence.getBody() == null ? "" : evidence.getBody();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
