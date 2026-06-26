package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ReplayLabFinalRemediationBriefResponse;
import com.etiya.replaylab.api.dto.ReplayLabJudgeModeStartRequest;
import com.etiya.replaylab.api.dto.ReplayLabJudgeModeStartResponse;
import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayCaseStatus;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ReplayLabJudgeModeService {

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ReplayLabDoraImpactScoreboardService doraImpactService;
    private final ReplayLabRemediationReadinessService readinessService;
    private final ReplayLabFinalRemediationBriefService finalBriefService;
    private final ObjectMapper objectMapper;

    public ReplayLabJudgeModeService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ReplayLabDoraImpactScoreboardService doraImpactService,
            ReplayLabRemediationReadinessService readinessService,
            ReplayLabFinalRemediationBriefService finalBriefService,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.doraImpactService = doraImpactService;
        this.readinessService = readinessService;
        this.finalBriefService = finalBriefService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReplayLabJudgeModeStartResponse start(ReplayLabJudgeModeStartRequest request) {
        ReplayLabJudgeModeStartRequest safe = safeRequest(request);
        ReplayCaseEntity replayCase = findOrCreateCase(safe);
        List<EvidenceEntity> evidence = evidenceRepository.findByCaseId(replayCase.getId());
        ReplayLabFinalRemediationBriefResponse brief = finalBriefService.response(replayCase, evidence);

        return new ReplayLabJudgeModeStartResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                firstNonBlank(replayCase.getEnvironment(), safe.environment(), "test2"),
                safe.demoMode(),
                doraImpactService.response(replayCase),
                readinessService.response(replayCase, evidence),
                agentsPreflightSummary(evidence),
                targetedPrPreviewSummary(evidence),
                brief.markdown(),
                List.of(
                        "No write action executed",
                        "No PR created",
                        "No Jenkins trigger executed",
                        "No Jira comment published"
                ),
                List.of(
                        "Open ReplayLab Incident Commander.",
                        "Show DORA Impact Scoreboard.",
                        "Show Remediation Readiness Score.",
                        "Run targeted PR preview.",
                        "Explain human approval gates."
                )
        );
    }

    private ReplayCaseEntity findOrCreateCase(ReplayLabJudgeModeStartRequest request) {
        Optional<ReplayCaseEntity> existing = caseRepository.findFirstByJiraKeyAndTargetKey(
                request.defectKey(),
                request.targetKey()
        );
        if (existing.isPresent()) {
            ReplayCaseEntity replayCase = existing.get();
            boolean changed = false;
            if (isBlank(replayCase.getEnvironment()) && !isBlank(request.environment())) {
                replayCase.setEnvironment(request.environment());
                changed = true;
            }
            if (request.demoMode() && !replayCase.isSynthetic()) {
                replayCase.setSynthetic(true);
                changed = true;
            }
            return changed ? caseRepository.save(replayCase) : replayCase;
        }

        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setJiraKey(request.defectKey());
        replayCase.setTargetKey(request.targetKey());
        replayCase.setEnvironment(request.environment());
        replayCase.setSynthetic(request.demoMode());
        replayCase.setStatus(ReplayCaseStatus.NEW);
        return caseRepository.save(replayCase);
    }

    private Map<String, Object> agentsPreflightSummary(List<EvidenceEntity> evidence) {
        return latest(evidence, EvidenceType.PULL_REQUEST)
                .map(this::map)
                .filter(value -> value.containsKey("reviewStatus")
                        || value.containsKey("rulesLoaded")
                        || text(value).contains("PR_RULE_PREFLIGHT_STATUS"))
                .map(value -> limited(value, List.of(
                        "reviewStatus",
                        "blockerViolationCount",
                        "rulesLoaded",
                        "blockers",
                        "warnings"
                )))
                .orElse(Map.of());
    }

    private Map<String, Object> targetedPrPreviewSummary(List<EvidenceEntity> evidence) {
        return latest(evidence, EvidenceType.PULL_REQUEST)
                .map(this::map)
                .map(value -> limited(value, List.of(
                        "created",
                        "previewOnly",
                        "filePath",
                        "changeMode",
                        "bugfixBranch",
                        "integrationBranch",
                        "pullRequestUrl",
                        "blockers",
                        "warnings"
                )))
                .orElse(Map.of());
    }

    private Optional<EvidenceEntity> latest(List<EvidenceEntity> evidence, EvidenceType type) {
        return evidence.stream()
                .filter(item -> item.getEvidenceType() == type)
                .max(Comparator.comparing(EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Map<String, Object> limited(Map<String, Object> value, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            if (value.containsKey(key)) {
                result.put(key, value.get(key));
            }
        }
        return sanitize(result);
    }

    private Map<String, Object> map(EvidenceEntity evidence) {
        try {
            return objectMapper.readValue(
                    text(evidence),
                    new TypeReference<>() {
                    }
            );
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(sanitize(key), sanitizeObject(item)));
        return result;
    }

    private Object sanitizeObject(Object value) {
        if (value instanceof String string) {
            return sanitize(string);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::sanitizeObject)
                    .toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(sanitize(String.valueOf(key)), sanitizeObject(item)));
            return result;
        }
        return value;
    }

    private String text(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "";
        }
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

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization", "[redacted]")
                .replaceAll("(?i)bearer", "[redacted]")
                .replaceAll("(?i)cookie", "[redacted]")
                .replaceAll("(?i)token", "[redacted]")
                .replaceAll("(?i)password", "[redacted]")
                .replaceAll("(?i)secret", "[redacted]")
                .replaceAll("(?i)apikey", "[redacted]")
                .replaceAll("(?i)privatekey", "[redacted]");
    }

    private ReplayLabJudgeModeStartRequest safeRequest(ReplayLabJudgeModeStartRequest request) {
        if (request == null) {
            return new ReplayLabJudgeModeStartRequest(
                    "",
                    "FIZZMS-10228",
                    "backend",
                    "test2",
                    true
            );
        }
        return new ReplayLabJudgeModeStartRequest(
                safe(request.requestedBy()),
                firstNonBlank(request.defectKey(), "FIZZMS-10228"),
                firstNonBlank(request.targetKey(), "backend"),
                firstNonBlank(request.environment(), "test2"),
                request.demoMode()
        );
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
