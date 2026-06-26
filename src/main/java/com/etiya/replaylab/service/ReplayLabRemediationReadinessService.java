package com.etiya.replaylab.service;

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

import java.util.List;
import java.util.UUID;

@Service
public class ReplayLabRemediationReadinessService {

    private static final List<String> GUARDRAILS = List.of(
            "No automatic PR merge",
            "No automatic deployment",
            "No direct push to target branch",
            "Human approval required for write actions"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;

    public ReplayLabRemediationReadinessService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Transactional(readOnly = true)
    public ReplayLabRemediationReadinessResponse readiness(UUID caseId) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId));
        List<EvidenceEntity> evidence = evidenceRepository.findByCaseId(caseId);
        return response(replayCase, evidence);
    }

    public ReplayLabRemediationReadinessResponse response(
            ReplayCaseEntity replayCase,
            List<EvidenceEntity> evidence
    ) {
        List<EvidenceEntity> safeEvidence = evidence == null ? List.of() : evidence;
        boolean demoBaseline = replayCase.isSynthetic();

        int evidencePoints = demoBaseline || hasAny(safeEvidence,
                EvidenceType.JIRA_ISSUE,
                EvidenceType.JENKINS_BUILD_CONTEXT,
                EvidenceType.REPOSITORY_RESOLUTION,
                EvidenceType.SOURCE_CHECKOUT) ? 20 : 0;
        int incidentVersionPoints = demoBaseline || hasAny(safeEvidence, EvidenceType.INCIDENT_VERSION) ? 25 : 0;
        int rcaPoints = demoBaseline || hasAny(safeEvidence,
                EvidenceType.DETERMINISTIC_ROOT_CAUSE,
                EvidenceType.ROVO_RCA,
                EvidenceType.ROOT_CAUSE_ANALYSIS,
                EvidenceType.AI_ROOT_CAUSE) ? 15 : 0;
        int preflightPoints = demoBaseline || hasPrPreflight(safeEvidence) ? 20 : 0;
        int jenkinsPoints = demoBaseline || hasAny(safeEvidence,
                EvidenceType.JENKINS_VALIDATION_PREVIEW,
                EvidenceType.JENKINS_VALIDATION_STATUS,
                EvidenceType.JENKINS_VALIDATION_RESULT,
                EvidenceType.JENKINS_VALIDATION_SUMMARY_PREVIEW) ? 2 : 0;

        List<ReplayLabRemediationReadinessResponse.ScoreBreakdownItem> breakdown = List.of(
                new ReplayLabRemediationReadinessResponse.ScoreBreakdownItem(
                        "Evidence completeness",
                        evidencePoints,
                        25,
                        evidencePoints > 0
                                ? "Jira, Jenkins and repository evidence are available or represented by the demo baseline."
                                : "Core Jira, Jenkins and repository evidence is not available yet."
                ),
                new ReplayLabRemediationReadinessResponse.ScoreBreakdownItem(
                        "Incident version validation",
                        incidentVersionPoints,
                        25,
                        incidentVersionPoints > 0
                                ? "Incident version validation succeeded or is represented by the demo baseline."
                                : "Incident version validation evidence is not available yet."
                ),
                new ReplayLabRemediationReadinessResponse.ScoreBreakdownItem(
                        "RCA evidence",
                        rcaPoints,
                        20,
                        rcaPoints > 0
                                ? "Deterministic RCA and Rovo RCA evidence are available or represented by the demo baseline, but runtime signals may be limited."
                                : "RCA evidence is not available yet."
                ),
                new ReplayLabRemediationReadinessResponse.ScoreBreakdownItem(
                        "AGENTS preflight",
                        preflightPoints,
                        20,
                        preflightPoints > 0
                                ? "AGENTS rule preflight accepted the planned change with zero blockers."
                                : "AGENTS rule preflight has not completed yet."
                ),
                new ReplayLabRemediationReadinessResponse.ScoreBreakdownItem(
                        "Jenkins validation readiness",
                        jenkinsPoints,
                        10,
                        jenkinsPoints > 0
                                ? "Jenkins validation summary flow is prepared but validation trigger/result may still require human approval."
                                : "Jenkins validation readiness evidence is not available yet."
                )
        );

        int score = breakdown.stream()
                .mapToInt(ReplayLabRemediationReadinessResponse.ScoreBreakdownItem::points)
                .sum();
        return new ReplayLabRemediationReadinessResponse(
                replayCase.getId(),
                safe(replayCase.getJiraKey()),
                score,
                verdict(score),
                breakdown,
                GUARDRAILS,
                List.of(
                        "Review the targeted defect PR path.",
                        "Trigger Jenkins validation after human approval.",
                        "Attach final ReplayLab remediation brief to Jira."
                )
        );
    }

    static String verdict(int score) {
        if (score < 40) {
            return "NOT_READY";
        }
        if (score < 70) {
            return "NEEDS_MORE_EVIDENCE";
        }
        if (score < 90) {
            return "READY_FOR_HUMAN_REVIEW";
        }
        return "READY_FOR_VALIDATION";
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
