package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.ApprovedWritePlanFile;
import com.etiya.replaylab.api.dto.ApprovedWritePlanResponse;
import com.etiya.replaylab.api.dto.ApprovedWritePlanTest;
import com.etiya.replaylab.api.dto.PatchPlanCandidateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovedWritePlanService {

    public static final String BLOCKED_BY_MISSING_APPROVAL =
            "BLOCKED_BY_MISSING_APPROVAL";

    private static final Logger log = LoggerFactory.getLogger(
            ApprovedWritePlanService.class
    );
    private static final List<String> GUARDRAILS = List.of(
            "NO_DIRECT_TEST2_COMMIT",
            "WORKSPACE_ONLY_WRITE",
            "HUMAN_APPROVAL_REQUIRED",
            "NO_SECRET_EXPOSURE",
            "NO_RAW_PROD_PAYLOAD",
            "NO_AUTO_PR",
            "NO_AUTO_JENKINS",
            "NO_ARGOCD_SYNC",
            "DRY_RUN_ONLY"
    );

    private final PatchPlanCandidateService patchPlanCandidateService;

    public ApprovedWritePlanService(
            PatchPlanCandidateService patchPlanCandidateService
    ) {
        this.patchPlanCandidateService = patchPlanCandidateService;
    }

    @Transactional(readOnly = true)
    public ApprovedWritePlanResponse plan(
            UUID caseId,
            String patchPlanId,
            boolean includeTestDraft,
            boolean includeFixDraft,
            boolean dryRun
    ) {
        PatchPlanCandidateResponse patchPlan =
                patchPlanCandidateService.candidate(
                        caseId,
                        false,
                        true,
                        includeTestDraft
                );
        List<String> warnings = new ArrayList<>(patchPlan.warnings());
        if (!isBlank(patchPlanId)) {
            warnings.add("PATCH_PLAN_ID_NOT_PERSISTED_YET");
        }
        if (!dryRun) {
            warnings.add("REAL_WORKSPACE_WRITE_NOT_ENABLED_USE_DRY_RUN");
        }

        String workspacePath = workspacePath(
                patchPlan.caseId(),
                patchPlan.targetRepository()
        );
        List<ApprovedWritePlanTest> plannedTests = includeTestDraft
                ? plannedTests(patchPlan)
                : List.of();
        List<ApprovedWritePlanFile> plannedFiles = plannedFiles(
                patchPlan,
                workspacePath,
                includeTestDraft,
                includeFixDraft,
                plannedTests
        );
        List<String> missingEvidence = missingEvidence(patchPlan);

        ApprovedWritePlanResponse response = new ApprovedWritePlanResponse(
                patchPlan.caseId(),
                patchPlan.jiraKey(),
                "HYPOTHESIS",
                BLOCKED_BY_MISSING_APPROVAL,
                true,
                true,
                true,
                patchPlan.targetRepository(),
                patchPlan.targetBranch(),
                patchPlan.proposedBranchName(),
                workspacePath,
                plannedFiles,
                plannedTests,
                plannedValidationCommands(plannedTests),
                missingEvidence,
                GUARDRAILS,
                unique(warnings),
                Instant.now()
        );
        log.info(
                "APPROVED_WRITE_PLAN_READY caseId={} jiraKey={} writePlanStatus={} dryRun={}",
                response.caseId(),
                response.jiraKey(),
                response.writePlanStatus(),
                response.dryRun()
        );
        return response;
    }

    private List<ApprovedWritePlanFile> plannedFiles(
            PatchPlanCandidateResponse patchPlan,
            String workspacePath,
            boolean includeTestDraft,
            boolean includeFixDraft,
            List<ApprovedWritePlanTest> plannedTests
    ) {
        List<ApprovedWritePlanFile> values = new ArrayList<>();
        if (includeTestDraft) {
            String testClass = plannedTests.isEmpty()
                    ? proposedTestClass(patchPlan)
                    : plannedTests.get(0).proposedClassName();
            values.add(new ApprovedWritePlanFile(
                    "REGRESSION_TEST",
                    workspacePath + "/src/test/java/com/etiya/replaylab/generated/"
                            + testClass + ".java",
                    patchPlan.targetClass(),
                    patchPlan.targetMethod(),
                    patchPlan.targetEndpoint(),
                    "",
                    "DRAFT",
                    false,
                    Map.of(
                            "scenario",
                            defaultScenario(patchPlan),
                            "testTypes",
                            testTypes(patchPlan)
                    )
            ));
        }
        if (includeFixDraft) {
            values.add(new ApprovedWritePlanFile(
                    "SOURCE_FIX",
                    sourceFixPath(patchPlan, workspacePath),
                    patchPlan.targetClass(),
                    patchPlan.targetMethod(),
                    patchPlan.targetEndpoint(),
                    patchPlan.recommendedChangeType(),
                    "DRAFT",
                    false,
                    Map.of(
                            "recommendedChange",
                            safeRecommendedChange(patchPlan),
                            "writesCode",
                            false,
                            "opensPullRequest",
                            false
                    )
            ));
        }
        return List.copyOf(values);
    }

    private List<ApprovedWritePlanTest> plannedTests(
            PatchPlanCandidateResponse patchPlan
    ) {
        List<ApprovedWritePlanTest> values = new ArrayList<>();
        List<Map<String, Object>> testPlan = patchPlan.testPlan();
        if (testPlan.isEmpty()) {
            values.add(test(
                    "API_INTEGRATION",
                    defaultScenario(patchPlan),
                    patchPlan,
                    Map.of()
            ));
            values.add(test(
                    "SERVICE_UNIT",
                    defaultScenario(patchPlan),
                    patchPlan,
                    Map.of()
            ));
            return List.copyOf(values);
        }
        for (Map<String, Object> entry : testPlan) {
            String type = string(entry.get("testType"));
            if (isBlank(type)) {
                continue;
            }
            String scenario = firstNonBlank(
                    string(entry.get("name")),
                    string(entry.get("scenario")),
                    string(entry.get("action")),
                    defaultScenario(patchPlan)
            );
            values.add(test(type, scenario, patchPlan, entry));
        }
        if (values.isEmpty()) {
            values.add(test(
                    "API_INTEGRATION",
                    defaultScenario(patchPlan),
                    patchPlan,
                    Map.of()
            ));
        }
        return List.copyOf(values);
    }

    private ApprovedWritePlanTest test(
            String testType,
            String scenario,
            PatchPlanCandidateResponse patchPlan,
            Map<String, Object> metadata
    ) {
        return new ApprovedWritePlanTest(
                testType,
                proposedTestClass(patchPlan),
                proposedTestMethod(testType, patchPlan),
                scenario,
                patchPlan.targetEndpoint(),
                "DRAFT",
                false,
                metadata
        );
    }

    private List<String> plannedValidationCommands(
            List<ApprovedWritePlanTest> plannedTests
    ) {
        List<String> values = new ArrayList<>();
        values.add("mvn clean compile -DskipTests");
        if (plannedTests.isEmpty()) {
            values.add("mvn test -Dtest=<targeted-regression-test>");
        } else {
            values.add("mvn test -Dtest="
                    + plannedTests.get(0).proposedClassName());
        }
        values.add("Jenkins validation is not triggered by this dry-run write plan");
        return List.copyOf(values);
    }

    private List<String> missingEvidence(
            PatchPlanCandidateResponse patchPlan
    ) {
        List<String> values = new ArrayList<>(patchPlan.missingEvidence());
        values.add(PatchPlanCandidateService.REPLAY_REPRODUCTION);
        values.add(PatchPlanCandidateService.FAILING_REGRESSION_TEST);
        values.add(PatchPlanCandidateService.JENKINS_VALIDATION);
        return unique(values);
    }

    private Map<String, Object> safeRecommendedChange(
            PatchPlanCandidateResponse patchPlan
    ) {
        Map<String, Object> value = new LinkedHashMap<>(
                patchPlan.recommendedCodeChange()
        );
        value.put("writesCode", false);
        value.put("opensPullRequest", false);
        value.put("requiresHumanApproval", true);
        value.remove("reasoning_content");
        value.remove("rawPrompt");
        value.remove("rawProductionPayload");
        return Map.copyOf(value);
    }

    private String sourceFixPath(
            PatchPlanCandidateResponse patchPlan,
            String workspacePath
    ) {
        String file = patchPlan.targetFiles().isEmpty()
                ? ""
                : patchPlan.targetFiles().get(0);
        if (isBlank(file)) {
            file = string(patchPlan.recommendedCodeChange().get("file"));
        }
        if (isBlank(file)) {
            file = "src/main/java/" + patchPlan.targetClass() + ".java";
        }
        return workspacePath + "/" + file.replace("\\", "/");
    }

    private String workspacePath(UUID caseId, String targetRepository) {
        String slug = "backend";
        if (!isBlank(targetRepository) && targetRepository.contains("/")) {
            slug = targetRepository.substring(targetRepository.lastIndexOf('/') + 1);
        } else if (!isBlank(targetRepository)) {
            slug = targetRepository;
        }
        return "work/" + caseId + "/repositories/" + slug;
    }

    private String proposedTestClass(PatchPlanCandidateResponse patchPlan) {
        String jira = firstNonBlank(patchPlan.jiraKey(), "case")
                .replaceAll("[^A-Za-z0-9]", "");
        String target = firstNonBlank(patchPlan.targetMethod(), "Replay");
        return jira + capitalize(target) + "RegressionTest";
    }

    private String proposedTestMethod(
            String testType,
            PatchPlanCandidateResponse patchPlan
    ) {
        String prefix = testType == null
                ? "replay"
                : testType.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_");
        return prefix + "_covers_"
                + firstNonBlank(patchPlan.targetMethod(), "incident");
    }

    private List<String> testTypes(PatchPlanCandidateResponse patchPlan) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> entry : patchPlan.testPlan()) {
            String value = string(entry.get("testType"));
            if (!isBlank(value)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            values.add("API_INTEGRATION");
            values.add("SERVICE_UNIT");
        }
        return unique(values);
    }

    private String defaultScenario(PatchPlanCandidateResponse patchPlan) {
        String combined = (patchPlan.targetEndpoint() + " "
                + patchPlan.targetClass() + " "
                + patchPlan.targetMethod()).toLowerCase(Locale.ROOT);
        if (combined.contains("region") || combined.contains("province")) {
            return "preferredProvince / region mismatch";
        }
        return "sanitized replay input regression scenario";
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

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String capitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
