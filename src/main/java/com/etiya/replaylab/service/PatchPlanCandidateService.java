package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.CodeChangeAdvisoryEvaluationSummaryResponse;
import com.etiya.replaylab.api.dto.CodeChangeAdvisoryResultSummary;
import com.etiya.replaylab.api.dto.PatchPlanCandidateResponse;
import com.etiya.replaylab.api.dto.RecommendedCodeChange;
import com.etiya.replaylab.api.dto.ReplayEnvironmentProvisionReadinessResponse;
import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.domain.ReplayEnvironmentRequestEntity;
import com.etiya.replaylab.model.FixPlanCandidate;
import com.etiya.replaylab.model.FixPlanResponse;
import com.etiya.replaylab.model.RegressionTestDbValidationRequirement;
import com.etiya.replaylab.model.RegressionTestDraftResponse;
import com.etiya.replaylab.model.RegressionTestScenario;
import com.etiya.replaylab.repository.EvidenceRepository;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import com.etiya.replaylab.repository.ReplayEnvironmentRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PatchPlanCandidateService {

    public static final String REPLAY_REPRODUCTION = "REPLAY_REPRODUCTION";
    public static final String FAILING_REGRESSION_TEST =
            "FAILING_REGRESSION_TEST";
    public static final String APPLICATION_DB_EVIDENCE =
            "APPLICATION_DB_EVIDENCE";
    public static final String REPLAY_ENVIRONMENT_PROVISIONING =
            "REPLAY_ENVIRONMENT_PROVISIONING";
    public static final String JENKINS_VALIDATION = "JENKINS_VALIDATION";

    private static final Logger log = LoggerFactory.getLogger(
            PatchPlanCandidateService.class
    );
    private static final List<String> ALLOWED_CHANGE_TYPES = List.of(
            "VALIDATION_GUARD",
            "NULL_GUARD",
            "MAPPING_FIX",
            "HTTP_STATUS_CODE_REPLACEMENT",
            "TRANSACTION_GUARD",
            "CONFIG_FIX",
            "UNKNOWN"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final RegressionTestDraftService regressionTestDraftService;
    private final FixPlanService fixPlanService;
    private final CodeChangeAdvisoryService advisoryService;
    private final ReplayEnvironmentRequestRepository replayRequestRepository;
    private final ReplayEnvironmentRequestService replayRequestService;
    private final ReplayLabProperties properties;

    public PatchPlanCandidateService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            RegressionTestDraftService regressionTestDraftService,
            FixPlanService fixPlanService,
            CodeChangeAdvisoryService advisoryService,
            ReplayEnvironmentRequestRepository replayRequestRepository,
            ReplayEnvironmentRequestService replayRequestService,
            ReplayLabProperties properties
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.regressionTestDraftService = regressionTestDraftService;
        this.fixPlanService = fixPlanService;
        this.advisoryService = advisoryService;
        this.replayRequestRepository = replayRequestRepository;
        this.replayRequestService = replayRequestService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PatchPlanCandidateResponse candidate(
            UUID caseId,
            boolean useCompanyLlm,
            boolean includeReplayReadiness,
            boolean includeRegressionDraft
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Replay case not found: " + caseId
                ));
        List<String> warnings = new ArrayList<>();

        RegressionTestDraftResponse regressionDraft = includeRegressionDraft
                ? regressionDraft(caseId, useCompanyLlm, warnings)
                : null;
        FixPlanResponse fixPlan = fixPlan(caseId, useCompanyLlm, warnings);
        CodeChangeAdvisoryEvaluationSummaryResponse advisorySummary =
                advisorySummary(caseId, warnings);
        ReplayEnvironmentProvisionReadinessResponse replayReadiness =
                includeReplayReadiness
                        ? replayReadiness(caseId, warnings)
                        : null;

        FixPlanCandidate selectedCandidate = fixPlan == null
                ? null
                : fixPlan.selectedCandidate();
        CodeChangeAdvisoryResultSummary backendAdvisory =
                advisorySummary == null
                        ? null
                        : advisorySummary.latestBackendMethodAdvisory();

        String targetEndpoint = firstNonBlank(
                regressionDraft == null ? "" : regressionDraft.targetEndpoint(),
                selectedCandidate == null ? "" : selectedCandidate.relatedFlow()
        );
        String targetClass = firstNonBlank(
                regressionDraft == null ? "" : regressionDraft.targetClass(),
                backendAdvisory == null ? "" : backendAdvisory.classOrComponentName(),
                selectedCandidate == null ? "" : selectedCandidate.targetClass()
        );
        String targetMethod = firstNonBlank(
                regressionDraft == null ? "" : regressionDraft.targetMethod(),
                backendAdvisory == null ? "" : backendAdvisory.methodName(),
                selectedCandidate == null ? "" : selectedCandidate.targetMethod()
        );
        String targetFile = firstNonBlank(
                backendAdvisory == null ? "" : backendAdvisory.filePath(),
                selectedCandidate == null ? "" : selectedCandidate.targetFile()
        );
        String changeType = recommendedChangeType(
                backendAdvisory,
                selectedCandidate,
                targetEndpoint,
                targetClass,
                targetMethod
        );

        List<String> missingEvidence = missingEvidence(
                caseId,
                regressionDraft,
                replayReadiness,
                includeReplayReadiness
        );
        String patchPlanStatus = patchPlanStatus(
                targetEndpoint,
                targetClass,
                targetMethod,
                regressionDraft,
                advisorySummary,
                missingEvidence
        );

        PatchPlanCandidateResponse response = new PatchPlanCandidateResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                "HYPOTHESIS",
                patchPlanStatus,
                false,
                true,
                targetRepository(replayCase),
                targetBranch(replayCase),
                proposedBranchName(replayCase),
                unique(nonBlank(targetFile)),
                unique(nonBlank(targetMethodReference(targetClass, targetMethod))),
                targetEndpoint,
                targetClass,
                targetMethod,
                changeType,
                recommendedCodeChange(
                        changeType,
                        targetFile,
                        targetClass,
                        targetMethod,
                        targetEndpoint,
                        backendAdvisory,
                        selectedCandidate
                ),
                riskReview(advisorySummary, selectedCandidate),
                testPlan(regressionDraft),
                dbValidationRequirements(regressionDraft),
                replayReadinessMap(replayReadiness),
                missingEvidence,
                unique(warnings),
                Instant.now()
        );
        log.info(
                "PATCH_PLAN_CANDIDATE_READY caseId={} jiraKey={} targetKey={} patchPlanStatus={}",
                replayCase.getId(),
                replayCase.getJiraKey(),
                replayCase.getTargetKey(),
                response.patchPlanStatus()
        );
        return response;
    }

    private RegressionTestDraftResponse regressionDraft(
            UUID caseId,
            boolean useCompanyLlm,
            List<String> warnings
    ) {
        try {
            return regressionTestDraftService.draft(caseId, useCompanyLlm, 3);
        } catch (Exception exception) {
            warnings.add("REGRESSION_TEST_DRAFT_UNAVAILABLE");
            log.warn(
                    "Patch plan candidate regression draft unavailable caseId={}",
                    caseId,
                    exception
            );
            return null;
        }
    }

    private FixPlanResponse fixPlan(
            UUID caseId,
            boolean useCompanyLlm,
            List<String> warnings
    ) {
        try {
            return fixPlanService.plan(caseId, useCompanyLlm, 5);
        } catch (Exception exception) {
            warnings.add("FIX_PLAN_UNAVAILABLE");
            log.warn(
                    "Patch plan candidate fix plan unavailable caseId={}",
                    caseId,
                    exception
            );
            return null;
        }
    }

    private CodeChangeAdvisoryEvaluationSummaryResponse advisorySummary(
            UUID caseId,
            List<String> warnings
    ) {
        try {
            return advisoryService.summary(caseId);
        } catch (Exception exception) {
            warnings.add("CODE_CHANGE_ADVISORY_SUMMARY_UNAVAILABLE");
            log.warn(
                    "Patch plan candidate advisory summary unavailable caseId={}",
                    caseId,
                    exception
            );
            return null;
        }
    }

    private ReplayEnvironmentProvisionReadinessResponse replayReadiness(
            UUID caseId,
            List<String> warnings
    ) {
        List<ReplayEnvironmentRequestEntity> requests =
                replayRequestRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
        if (requests.isEmpty()) {
            warnings.add("REPLAY_ENVIRONMENT_REQUEST_NOT_FOUND");
            return null;
        }
        try {
            return replayRequestService.provisionReadiness(
                    requests.get(0).getId()
            );
        } catch (Exception exception) {
            warnings.add("REPLAY_ENVIRONMENT_READINESS_UNAVAILABLE");
            log.warn(
                    "Patch plan candidate replay readiness unavailable caseId={}",
                    caseId,
                    exception
            );
            return null;
        }
    }

    private List<String> missingEvidence(
            UUID caseId,
            RegressionTestDraftResponse regressionDraft,
            ReplayEnvironmentProvisionReadinessResponse readiness,
            boolean includeReplayReadiness
    ) {
        List<String> values = new ArrayList<>();
        if (!hasEvidence(caseId, EvidenceType.REPLAY_OUTPUT)) {
            values.add(REPLAY_REPRODUCTION);
        }
        if (!hasEvidence(caseId, EvidenceType.GENERATED_TEST)) {
            values.add(FAILING_REGRESSION_TEST);
        }
        if ((regressionDraft != null && regressionDraft.requiresDbEvidence())
                || !hasEvidence(caseId, EvidenceType.APPLICATION_DB_EVIDENCE)) {
            values.add(APPLICATION_DB_EVIDENCE);
        }
        if (!includeReplayReadiness
                || readiness == null
                || !"READY".equals(readiness.readinessStatus())) {
            values.add(REPLAY_ENVIRONMENT_PROVISIONING);
        }
        if (!hasEvidence(caseId, EvidenceType.JENKINS_RESULT)) {
            values.add(JENKINS_VALIDATION);
        }
        return unique(values);
    }

    private boolean hasEvidence(UUID caseId, EvidenceType evidenceType) {
        return !evidenceRepository
                .findByCaseIdAndEvidenceType(caseId, evidenceType)
                .isEmpty();
    }

    private String patchPlanStatus(
            String targetEndpoint,
            String targetClass,
            String targetMethod,
            RegressionTestDraftResponse regressionDraft,
            CodeChangeAdvisoryEvaluationSummaryResponse advisorySummary,
            List<String> missingEvidence
    ) {
        boolean sourceKnown = !isBlank(targetEndpoint)
                || (!isBlank(targetClass) && !isBlank(targetMethod));
        if (!sourceKnown) {
            return "NEEDS_MORE_EVIDENCE";
        }
        if (regressionDraft == null) {
            return "NEEDS_MORE_EVIDENCE";
        }
        if (advisorySummary == null
                || advisorySummary.advisoryGeneratedCount() == 0) {
            return missingEvidence.isEmpty()
                    ? "READY_FOR_HUMAN_REVIEW"
                    : "DRAFT";
        }
        return "DRAFT";
    }

    private String recommendedChangeType(
            CodeChangeAdvisoryResultSummary advisory,
            FixPlanCandidate selectedCandidate,
            String targetEndpoint,
            String targetClass,
            String targetMethod
    ) {
        String value = advisory == null
                ? ""
                : advisory.changeType();
        if (isBlank(value) && selectedCandidate != null) {
            value = selectedCandidate.fixType();
        }
        if (isBlank(value)) {
            String combined = (targetEndpoint + " " + targetClass + " "
                    + targetMethod).toLowerCase(Locale.ROOT);
            if (combined.contains("region")
                    || combined.contains("province")) {
                value = "MAPPING_FIX";
            } else {
                value = "VALIDATION_GUARD";
            }
        }
        value = switch (value) {
            case "NULL_SAFETY" -> "NULL_GUARD";
            case "CONFIG_FALLBACK" -> "CONFIG_FIX";
            default -> value;
        };
        return ALLOWED_CHANGE_TYPES.contains(value) ? value : "UNKNOWN";
    }

    private Map<String, Object> recommendedCodeChange(
            String changeType,
            String file,
            String targetClass,
            String targetMethod,
            String targetEndpoint,
            CodeChangeAdvisoryResultSummary advisory,
            FixPlanCandidate selectedCandidate
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("file", file);
        value.put("targetClass", targetClass);
        value.put("methodName", targetMethod);
        value.put("targetEndpoint", targetEndpoint);
        value.put("changeType", changeType);
        value.put("description", firstNonBlank(
                advisory == null || advisory.recommendedCodeChange() == null
                        ? ""
                        : advisory.recommendedCodeChange().description(),
                selectedCandidate == null ? "" : selectedCandidate.reason(),
                "Draft-only candidate. Add validation or mapping changes only after replay and test evidence are reviewed."
        ));
        value.put("pseudoPatch", "");
        value.put("writesCode", false);
        value.put("opensPullRequest", false);
        value.put("requiresHumanApproval", true);
        return Map.copyOf(value);
    }

    private List<Map<String, Object>> riskReview(
            CodeChangeAdvisoryEvaluationSummaryResponse summary,
            FixPlanCandidate selectedCandidate
    ) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (summary != null && summary.latestRiskReview() != null) {
            for (String risk : summary.latestRiskReview().risks()) {
                values.add(Map.of(
                        "risk", risk,
                        "source", "CODE_CHANGE_ADVISORY",
                        "status", "HYPOTHESIS"
                ));
            }
        }
        if (selectedCandidate != null && !isBlank(selectedCandidate.riskLevel())) {
            values.add(Map.of(
                    "risk", "Candidate risk level: "
                            + selectedCandidate.riskLevel(),
                    "source", "FIX_PLAN",
                    "status", "HYPOTHESIS"
            ));
        }
        if (values.isEmpty()) {
            values.add(Map.of(
                    "risk", "Patch not authorized until replay and regression evidence are reviewed.",
                    "source", "PATCH_PLAN_CANDIDATE",
                    "status", "HYPOTHESIS"
            ));
        }
        return List.copyOf(values);
    }

    private List<Map<String, Object>> testPlan(
            RegressionTestDraftResponse regressionDraft
    ) {
        if (regressionDraft == null) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (String type : regressionDraft.testTypeCandidates()) {
            values.add(Map.of(
                    "testType", type,
                    "targetEndpoint", regressionDraft.targetEndpoint(),
                    "targetClass", regressionDraft.targetClass(),
                    "targetMethod", regressionDraft.targetMethod(),
                    "status", "DRAFT"
            ));
        }
        for (RegressionTestScenario scenario : regressionDraft.scenarios()) {
            values.add(Map.of(
                    "testType", scenario.testType(),
                    "name", scenario.name(),
                    "targetEndpoint", scenario.targetEndpoint(),
                    "targetClass", scenario.targetClass(),
                    "targetMethod", scenario.targetMethod(),
                    "action", scenario.action(),
                    "expectedResult", scenario.expectedResult(),
                    "status", "DRAFT"
            ));
        }
        return List.copyOf(values);
    }

    private List<String> dbValidationRequirements(
            RegressionTestDraftResponse regressionDraft
    ) {
        if (regressionDraft == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (RegressionTestDbValidationRequirement requirement :
                regressionDraft.dbValidationRequirements()) {
            values.add(requirement.templateId());
        }
        return unique(values);
    }

    private Map<String, Object> replayReadinessMap(
            ReplayEnvironmentProvisionReadinessResponse readiness
    ) {
        if (readiness == null) {
            return Map.of(
                    "readinessStatus", "UNKNOWN",
                    "requestApproved", false,
                    "realProvisioningEnabled", false,
                    "dryRunOnly", true,
                    "blockers", List.of(REPLAY_ENVIRONMENT_PROVISIONING)
            );
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requestId", readiness.requestId());
        value.put("requestStatus", readiness.requestStatus());
        value.put("readinessStatus", readiness.readinessStatus());
        value.put("requestApproved", readiness.requestApproved());
        value.put("realProvisioningEnabled", readiness.realProvisioningEnabled());
        value.put("dryRunOnly", readiness.dryRunOnly());
        value.put("namespaceConfigured", readiness.namespaceConfigured());
        value.put("argocdProjectConfigured", readiness.argocdProjectConfigured());
        value.put("replayNamespace", readiness.replayNamespace());
        value.put("proposedHost", readiness.proposedHost());
        value.put("blockers", readiness.blockers());
        value.put("warnings", readiness.warnings());
        return Map.copyOf(value);
    }

    private String targetRepository(ReplayCaseEntity replayCase) {
        ReplayLabProperties.Target target = target(replayCase);
        ReplayLabProperties.SourceCandidateRepository backend =
                target.getBitbucket().getRepositories().get("backend");
        String project = backend == null ? "" : backend.getProjectKey();
        String slug = backend == null ? "" : backend.getRepositorySlug();
        if (!isBlank(project) && !isBlank(slug)) {
            return project + "/" + slug;
        }
        if (!isBlank(target.getBackendProjectKey())
                && !isBlank(target.getBackendRepositorySlug())) {
            return target.getBackendProjectKey()
                    + "/"
                    + target.getBackendRepositorySlug();
        }
        return firstNonBlank(target.getRepository(), replayCase.getTargetKey());
    }

    private String targetBranch(ReplayCaseEntity replayCase) {
        ReplayLabProperties.Target target = target(replayCase);
        ReplayLabProperties.SourceCandidateRepository backend =
                target.getBitbucket().getRepositories().get("backend");
        return firstNonBlank(
                replayCase.getSourceBranch(),
                backend == null ? "" : backend.getBranch(),
                target.getBackendTargetRevision(),
                target.getDefaultBranch(),
                target.getGit().getSourceBranch(),
                "test2"
        );
    }

    private ReplayLabProperties.Target target(ReplayCaseEntity replayCase) {
        return properties.getTargets()
                .getOrDefault(
                        replayCase.getTargetKey(),
                        new ReplayLabProperties.Target()
                );
    }

    private String proposedBranchName(ReplayCaseEntity replayCase) {
        return "bugfix/"
                + firstNonBlank(replayCase.getJiraKey(), "case")
                + "-replaylab";
    }

    private String targetMethodReference(
            String targetClass,
            String targetMethod
    ) {
        if (isBlank(targetClass) && isBlank(targetMethod)) {
            return "";
        }
        if (isBlank(targetClass)) {
            return targetMethod;
        }
        if (isBlank(targetMethod)) {
            return targetClass;
        }
        return targetClass + "#" + targetMethod;
    }

    private List<String> nonBlank(String value) {
        return isBlank(value) ? List.of() : List.of(value);
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(
                values == null
                        ? List.of()
                        : values.stream()
                        .filter(value -> !isBlank(value))
                        .toList()
        ));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
