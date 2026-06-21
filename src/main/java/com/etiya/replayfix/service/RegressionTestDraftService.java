package com.etiya.replayfix.service;

import com.etiya.replayfix.model.ApplicationDbEvidenceQueryTemplate;
import com.etiya.replayfix.model.ApplicationDbEvidenceResponse;
import com.etiya.replayfix.model.FixPlanResponse;
import com.etiya.replayfix.model.RegressionTestAssertion;
import com.etiya.replayfix.model.RegressionTestDataRequirement;
import com.etiya.replayfix.model.RegressionTestDbValidationRequirement;
import com.etiya.replayfix.model.RegressionTestDraftResponse;
import com.etiya.replayfix.model.RegressionTestMockRequirement;
import com.etiya.replayfix.model.RegressionTestScenario;
import com.etiya.replayfix.model.RegressionTestStep;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegressionTestDraftService {

    public static final String REGRESSION_DRAFT_SOURCE_ANALYSIS_FAILED =
            "REGRESSION_DRAFT_SOURCE_ANALYSIS_FAILED";
    public static final String REGRESSION_DRAFT_FIX_PLAN_FAILED =
            "REGRESSION_DRAFT_FIX_PLAN_FAILED";
    public static final String REGRESSION_DRAFT_DB_EVIDENCE_FAILED =
            "REGRESSION_DRAFT_DB_EVIDENCE_FAILED";

    private static final Logger log = LoggerFactory.getLogger(
            RegressionTestDraftService.class
    );

    private final SourceSuspectChangeAnalysisService sourceAnalysisService;
    private final FixPlanService fixPlanService;
    private final ApplicationDbEvidenceService dbEvidenceService;

    public RegressionTestDraftService(
            SourceSuspectChangeAnalysisService sourceAnalysisService,
            FixPlanService fixPlanService,
            ApplicationDbEvidenceService dbEvidenceService
    ) {
        this.sourceAnalysisService = sourceAnalysisService;
        this.fixPlanService = fixPlanService;
        this.dbEvidenceService = dbEvidenceService;
    }

    @Transactional(readOnly = true)
    public RegressionTestDraftResponse draft(
            UUID caseId,
            boolean useCompanyLlm,
            int maxScenarios
    ) {
        List<String> warnings = new ArrayList<>();
        SourceSuspectChangeAnalysisResponse sourceAnalysis =
                sourceAnalysis(caseId, useCompanyLlm, warnings);
        FixPlanResponse fixPlan = fixPlan(caseId, warnings);
        ApplicationDbEvidenceResponse dbEvidence = dbEvidence(caseId, warnings);

        warnings.addAll(sourceAnalysis.warnings());
        warnings.addAll(fixPlan.warnings());
        warnings.addAll(dbEvidence.warnings());
        addCompanyLlmWarning(sourceAnalysis, warnings);

        String targetEndpoint = targetEndpoint(sourceAnalysis, fixPlan);
        Optional<SourceCandidateFlowChainItem> serviceTarget =
                serviceTarget(sourceAnalysis);
        String targetClass = serviceTarget
                .map(SourceCandidateFlowChainItem::className)
                .filter(value -> !safeString(value).isBlank())
                .orElseGet(() -> firstNonBlank(
                        fixPlan.selectedCandidate() == null
                                ? ""
                                : fixPlan.selectedCandidate().targetClass(),
                        ""
                ));
        String targetMethod = serviceTarget
                .map(SourceCandidateFlowChainItem::methodName)
                .filter(value -> !safeString(value).isBlank())
                .orElseGet(() -> firstNonBlank(
                        fixPlan.selectedCandidate() == null
                                ? ""
                                : fixPlan.selectedCandidate().targetMethod(),
                        ""
                ));
        List<String> testTypeCandidates = testTypeCandidates(
                targetEndpoint,
                serviceTarget.isPresent()
        );
        String selectedTestType = selectedTestType(
                targetEndpoint,
                serviceTarget.isPresent()
        );
        boolean regionUpdate = isRegionUpdate(sourceAnalysis, fixPlan);
        List<RegressionTestScenario> scenarios = scenarios(
                selectedTestType,
                targetEndpoint,
                targetClass,
                targetMethod,
                regionUpdate,
                Math.max(1, maxScenarios)
        );

        return new RegressionTestDraftResponse(
                caseId,
                firstNonBlank(sourceAnalysis.jiraKey(), fixPlan.jiraKey()),
                "HYPOTHESIS",
                testTypeCandidates,
                selectedTestType,
                targetEndpoint,
                targetClass,
                targetMethod,
                scenarios,
                dataRequirements(regionUpdate),
                mockRequirements(targetClass, targetMethod),
                dbValidationRequirements(dbEvidence.queryTemplates()),
                fixPlan.requiresDbEvidence(),
                true,
                unique(warnings)
        );
    }

    private SourceSuspectChangeAnalysisResponse sourceAnalysis(
            UUID caseId,
            boolean useCompanyLlm,
            List<String> warnings
    ) {
        try {
            return sourceAnalysisService.analyze(
                    caseId,
                    45,
                    20,
                    10,
                    false,
                    useCompanyLlm,
                    2_000,
                    256,
                    false,
                    10,
                    8,
                    useCompanyLlm ? 45 : 8,
                    "MINIMAL",
                    12_000,
                    useCompanyLlm ? 3_000 : 500
            );
        } catch (Exception exception) {
            log.warn(
                    "Regression test draft source analysis failed for caseId={}",
                    caseId,
                    exception
            );
            warnings.add(REGRESSION_DRAFT_SOURCE_ANALYSIS_FAILED);
            return emptySourceAnalysis(caseId);
        }
    }

    private FixPlanResponse fixPlan(UUID caseId, List<String> warnings) {
        try {
            return fixPlanService.plan(caseId, false, 5);
        } catch (Exception exception) {
            log.warn(
                    "Regression test draft fix plan failed for caseId={}",
                    caseId,
                    exception
            );
            warnings.add(REGRESSION_DRAFT_FIX_PLAN_FAILED);
            return new FixPlanResponse(
                    caseId,
                    "",
                    "HYPOTHESIS",
                    0.0,
                    List.of(),
                    null,
                    List.of(),
                    List.of(FixPlanService.APPLICATION_DB_EVIDENCE),
                    true,
                    true,
                    List.of(REGRESSION_DRAFT_FIX_PLAN_FAILED)
            );
        }
    }

    private ApplicationDbEvidenceResponse dbEvidence(
            UUID caseId,
            List<String> warnings
    ) {
        try {
            return dbEvidenceService.collect(caseId, "backend", false, 20);
        } catch (Exception exception) {
            log.warn(
                    "Regression test draft DB evidence lookup failed for caseId={}",
                    caseId,
                    exception
            );
            warnings.add(REGRESSION_DRAFT_DB_EVIDENCE_FAILED);
            return new ApplicationDbEvidenceResponse(
                    caseId,
                    "",
                    "HYPOTHESIS",
                    "backend",
                    true,
                    List.of(),
                    List.of(),
                    List.of(),
                    true,
                    List.of(REGRESSION_DRAFT_DB_EVIDENCE_FAILED)
            );
        }
    }

    private List<String> testTypeCandidates(
            String endpoint,
            boolean serviceImplementationExists
    ) {
        List<String> values = new ArrayList<>();
        if (!safeString(endpoint).isBlank()) {
            values.add("API_INTEGRATION");
        }
        if (serviceImplementationExists) {
            values.add("SERVICE_UNIT");
        }
        if (values.isEmpty()) {
            values.add("UNKNOWN");
        }
        return unique(values);
    }

    private String selectedTestType(
            String endpoint,
            boolean serviceImplementationExists
    ) {
        if (!safeString(endpoint).isBlank()) {
            return "API_INTEGRATION";
        }
        if (serviceImplementationExists) {
            return "SERVICE_UNIT";
        }
        return "UNKNOWN";
    }

    private List<RegressionTestScenario> scenarios(
            String selectedTestType,
            String endpoint,
            String targetClass,
            String targetMethod,
            boolean regionUpdate,
            int maxScenarios
    ) {
        List<RegressionTestScenario> values = new ArrayList<>();
        if (regionUpdate) {
            values.add(regionUpdateScenario(
                    selectedTestType,
                    endpoint,
                    targetClass,
                    targetMethod
            ));
        } else {
            values.add(genericScenario(
                    selectedTestType,
                    endpoint,
                    targetClass,
                    targetMethod
            ));
        }
        return values.stream().limit(maxScenarios).toList();
    }

    private RegressionTestScenario regionUpdateScenario(
            String selectedTestType,
            String endpoint,
            String targetClass,
            String targetMethod
    ) {
        return new RegressionTestScenario(
                "Region update rejects or normalizes inconsistent preferred province",
                selectedTestType,
                endpoint,
                targetClass,
                targetMethod,
                List.of(
                        "User has an existing region/preferred province state.",
                        "DB evidence is required before confirming the exact persistence behavior."
                ),
                "Call /user/region/update with UpdateAplUserPrefPrvncRequest containing a province/region combination that may be inconsistent.",
                "The application should reject, normalize, or safely handle invalid province-region-tax-timezone combinations according to the business rule.",
                List.of(
                        new RegressionTestStep(
                                1,
                                "Load sanitized user and original region state.",
                                "userId or customerId, original preferred province, expected mapping",
                                "Baseline state is available for hypothesis validation."
                        ),
                        new RegressionTestStep(
                                2,
                                "Submit region update request.",
                                "UpdateAplUserPrefPrvncRequest with inconsistent preferredProvince/region values",
                                "API and service behavior can be observed without writing a test file."
                        ),
                        new RegressionTestStep(
                                3,
                                "Validate response and persisted state using read-only evidence.",
                                "USER_PREFERRED_PROVINCE, USER_REGION_STATE, TAX_INFO_STATE, TIMEZONE_STATE",
                                "No silent inconsistent user profile, billing, tax or timezone state remains."
                        )
                ),
                List.of(
                        new RegressionTestAssertion(
                                "API",
                                "API response should not silently accept invalid state.",
                                "Response rejects, normalizes, or explicitly reports the inconsistent request."
                        ),
                        new RegressionTestAssertion(
                                "SERVICE",
                                "Service should not leave user profile in inconsistent region state.",
                                "UserServiceImpl#updateUser applies a safe business-rule outcome."
                        ),
                        new RegressionTestAssertion(
                                "DB",
                                "No partial update should leave billing/tax/timezone state inconsistent.",
                                "Read-only DB validation confirms related state remains coherent."
                        )
                ),
                List.of("DRAFT_ONLY", "NOT_REPRODUCED", "NOT_VERIFIED")
        );
    }

    private RegressionTestScenario genericScenario(
            String selectedTestType,
            String endpoint,
            String targetClass,
            String targetMethod
    ) {
        return new RegressionTestScenario(
                "Regression behavior remains hypothesis until evidence is confirmed",
                selectedTestType,
                endpoint,
                targetClass,
                targetMethod,
                List.of("ReplayFix source reasoning selected a candidate path."),
                "Exercise the selected endpoint or service method with sanitized evidence.",
                "The application should handle the suspected defect path according to the expected business rule.",
                List.of(),
                List.of(new RegressionTestAssertion(
                        "BEHAVIOR",
                        "Draft must be confirmed by human review before writing tests.",
                        "Status remains HYPOTHESIS."
                )),
                List.of("DRAFT_ONLY", "NOT_REPRODUCED", "NOT_VERIFIED")
        );
    }

    private List<RegressionTestDataRequirement> dataRequirements(
            boolean regionUpdate
    ) {
        if (!regionUpdate) {
            return List.of(new RegressionTestDataRequirement(
                    "sanitized request body sample",
                    "Sanitized input for the selected candidate behavior.",
                    true,
                    "ReplayFix evidence"
            ));
        }
        return List.of(
                new RegressionTestDataRequirement(
                        "userId or customerId",
                        "Identifier for a user affected by /user/region/update.",
                        true,
                        "Jira/Application DB evidence"
                ),
                new RegressionTestDataRequirement(
                        "original preferred province",
                        "Existing preferred province before the update.",
                        true,
                        "APPLICATION_DB_EVIDENCE"
                ),
                new RegressionTestDataRequirement(
                        "requested preferred province",
                        "Requested preferred province sent in UpdateAplUserPrefPrvncRequest.",
                        true,
                        "Sanitized request sample"
                ),
                new RegressionTestDataRequirement(
                        "expected province-region mapping",
                        "Business rule mapping for province and region consistency.",
                        true,
                        "Application DB evidence or domain reference"
                ),
                new RegressionTestDataRequirement(
                        "expected tax/timezone/billing region state",
                        "Expected related state after the update path.",
                        true,
                        "APPLICATION_DB_EVIDENCE"
                ),
                new RegressionTestDataRequirement(
                        "sanitized request body sample",
                        "Sanitized UpdateAplUserPrefPrvncRequest sample without secrets or raw logs.",
                        true,
                        "ReplayFix evidence"
                )
        );
    }

    private List<RegressionTestMockRequirement> mockRequirements(
            String targetClass,
            String targetMethod
    ) {
        return List.of(
                new RegressionTestMockRequirement(
                        "repository/DAO calls behind " + firstNonBlank(targetClass, "UserServiceImpl")
                                + "#" + firstNonBlank(targetMethod, "updateUser"),
                        "If API integration test is not possible, mock persistence collaborators behind the service method.",
                        true
                ),
                new RegressionTestMockRequirement(
                        "external CRM/customer services",
                        "Mock external services if the selected path calls outside ReplayFix-controlled code.",
                        false
                ),
                new RegressionTestMockRequirement(
                        firstNonBlank(targetClass, "method under test")
                                + "#" + firstNonBlank(targetMethod, ""),
                        "Do not mock the method under test itself.",
                        false
                )
        );
    }

    private List<RegressionTestDbValidationRequirement> dbValidationRequirements(
            List<ApplicationDbEvidenceQueryTemplate> templates
    ) {
        return safeList(templates)
                .stream()
                .filter(template -> !"UNKNOWN".equalsIgnoreCase(template.templateId()))
                .map(template -> new RegressionTestDbValidationRequirement(
                        template.templateId(),
                        template.name(),
                        template.purpose(),
                        template.requiredInputs(),
                        template.tables(),
                        template.columns()
                ))
                .toList();
    }

    private String targetEndpoint(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            FixPlanResponse fixPlan
    ) {
        return sourceAnalysis.matchedEndpointAnchors()
                .stream()
                .filter(value -> safeString(value).startsWith("/"))
                .findFirst()
                .orElseGet(() -> fixPlan.selectedCandidate() == null
                        ? ""
                        : startsWithSlash(fixPlan.selectedCandidate().relatedFlow()));
    }

    private Optional<SourceCandidateFlowChainItem> serviceTarget(
            SourceSuspectChangeAnalysisResponse sourceAnalysis
    ) {
        return firstByLayer(sourceAnalysis.candidateFlowChain(), "SERVICE_IMPL")
                .or(() -> firstByLayer(
                        sourceAnalysis.candidateFlowChain(),
                        "SERVICE"
                ));
    }

    private Optional<SourceCandidateFlowChainItem> firstByLayer(
            List<SourceCandidateFlowChainItem> chain,
            String layer
    ) {
        return safeList(chain).stream()
                .filter(item -> layer.equalsIgnoreCase(item.layer()))
                .findFirst();
    }

    private boolean isRegionUpdate(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            FixPlanResponse fixPlan
    ) {
        StringBuilder builder = new StringBuilder();
        sourceAnalysis.matchedEndpointAnchors()
                .forEach(value -> builder.append(' ').append(value));
        sourceAnalysis.candidateFlowChain().forEach(item -> {
            builder.append(' ').append(item.className());
            builder.append(' ').append(item.methodName());
            builder.append(' ').append(item.relatedSignals());
        });
        sourceAnalysis.candidateMethods().forEach(method -> {
            builder.append(' ').append(method.className());
            builder.append(' ').append(method.methodName());
            builder.append(' ').append(method.snippet());
        });
        if (fixPlan.selectedCandidate() != null) {
            builder.append(' ').append(fixPlan.selectedCandidate().relatedFlow());
            builder.append(' ').append(fixPlan.selectedCandidate().relatedSignals());
        }
        String value = builder.toString().toLowerCase(Locale.ROOT);
        return value.contains("/user/region/update")
                || value.contains("preferredprovince")
                || value.contains("preferred province")
                || value.contains("updateapluserprefprvncrequest");
    }

    private void addCompanyLlmWarning(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            List<String> warnings
    ) {
        if (sourceAnalysis.llmUsed()
                || "NOT_REQUESTED".equals(sourceAnalysis.companyLlmStatus())
                || "SUCCESS".equals(sourceAnalysis.companyLlmStatus())) {
            return;
        }
        if ("TIMEOUT".equals(sourceAnalysis.companyLlmStatus())) {
            warnings.add(CompanySourceReasoningService.COMPANY_LLM_TIMEOUT);
        } else if ("ERROR".equals(sourceAnalysis.companyLlmStatus())) {
            warnings.add(CompanySourceReasoningService
                    .COMPANY_LLM_INVALID_RESPONSE);
        } else if ("UNAVAILABLE".equals(sourceAnalysis.companyLlmStatus())) {
            warnings.add(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
        }
    }

    private SourceSuspectChangeAnalysisResponse emptySourceAnalysis(UUID caseId) {
        return new SourceSuspectChangeAnalysisResponse(
                caseId,
                "",
                "",
                "",
                "",
                45,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                "HYPOTHESIS",
                0.0,
                List.of(REGRESSION_DRAFT_SOURCE_ANALYSIS_FAILED),
                "DETERMINISTIC_ONLY",
                true
        );
    }

    private String startsWithSlash(String value) {
        String safe = safeString(value);
        return safe.startsWith("/") ? safe : "";
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
