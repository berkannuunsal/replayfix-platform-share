package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.FixPlanCandidate;
import com.etiya.replayfix.model.FixPlanEvidenceReference;
import com.etiya.replayfix.model.FixPlanResponse;
import com.etiya.replayfix.model.PatchRuleReference;
import com.etiya.replayfix.model.SourceCandidateFlowChainItem;
import com.etiya.replayfix.model.SourceCandidateMethod;
import com.etiya.replayfix.model.SourceLastCommitDiagnostic;
import com.etiya.replayfix.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
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
public class FixPlanService {

    public static final String NO_RECENT_CHANGE_EVIDENCE =
            "NO_RECENT_CHANGE_EVIDENCE";
    public static final String APPLICATION_DB_EVIDENCE =
            "APPLICATION_DB_EVIDENCE";
    public static final String FIX_PLAN_SOURCE_ANALYSIS_FAILED =
            "FIX_PLAN_SOURCE_ANALYSIS_FAILED";

    private static final Logger log = LoggerFactory.getLogger(
            FixPlanService.class
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final SourceSuspectChangeAnalysisService sourceAnalysisService;
    private final PatchRuleRegistry patchRuleRegistry;

    public FixPlanService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            SourceSuspectChangeAnalysisService sourceAnalysisService,
            PatchRuleRegistry patchRuleRegistry
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.sourceAnalysisService = sourceAnalysisService;
        this.patchRuleRegistry = patchRuleRegistry;
    }

    @Transactional(readOnly = true)
    public FixPlanResponse plan(
            UUID caseId,
            boolean useCompanyLlm,
            int maxCandidates
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseGet(() -> defaultCase(caseId));
        SourceSuspectChangeAnalysisResponse sourceAnalysis;
        List<String> warnings = new ArrayList<>();
        try {
            sourceAnalysis = sourceAnalysisService.analyze(
                    caseId,
                    45,
                    Math.max(5, maxCandidates),
                    10,
                    false,
                    useCompanyLlm,
                    2_000,
                    256,
                    false,
                    10,
                    8,
                    8,
                    "MINIMAL",
                    12_000,
                    500
            );
        } catch (Exception exception) {
            log.warn(
                    "Fix plan source analysis failed for caseId={}",
                    caseId,
                    exception
            );
            warnings.add(FIX_PLAN_SOURCE_ANALYSIS_FAILED);
            sourceAnalysis = emptySourceAnalysis(replayCase);
        }

        warnings.addAll(sourceAnalysis.warnings());
        if (useCompanyLlm && !sourceAnalysis.llmUsed()
                && !"NOT_REQUESTED".equals(sourceAnalysis.companyLlmStatus())
                && !"SUCCESS".equals(sourceAnalysis.companyLlmStatus())) {
            warnings.add(CompanySourceReasoningService.COMPANY_LLM_UNAVAILABLE);
        }

        SourceCandidateFlowChainItem target = preferredTarget(
                sourceAnalysis.candidateFlowChain()
        ).orElse(null);
        String combinedSignals = combinedSignals(sourceAnalysis);
        boolean dbEvidenceRequired = requiresDatabaseEvidence(combinedSignals);
        Optional<EvidenceEntity> dbEvidence = applicationDbEvidence(caseId);
        boolean hasDbEvidence = dbEvidence.isPresent();
        boolean requiresDbEvidence = dbEvidenceRequired && !hasDbEvidence;
        if (sourceAnalysis.recentCommits().isEmpty()) {
            warnings.add(NO_RECENT_CHANGE_EVIDENCE);
        }

        List<String> missingEvidence = new ArrayList<>();
        if (requiresDbEvidence) {
            missingEvidence.add(APPLICATION_DB_EVIDENCE);
        }

        List<FixPlanEvidenceReference> requiredEvidence = requiredEvidence(
                sourceAnalysis,
                requiresDbEvidence,
                dbEvidence
        );
        List<FixPlanCandidate> candidates = candidates(
                sourceAnalysis,
                target,
                combinedSignals,
                requiresDbEvidence,
                hasDbEvidence,
                warnings,
                Math.max(1, maxCandidates)
        );
        FixPlanCandidate selectedCandidate = candidates.isEmpty()
                ? null
                : candidates.get(0);
        double confidence = selectedCandidate == null
                ? 0.0
                : selectedCandidate.confidence();

        return new FixPlanResponse(
                caseId,
                firstNonBlank(sourceAnalysis.jiraKey(), replayCase.getJiraKey()),
                "HYPOTHESIS",
                confidence,
                candidates,
                selectedCandidate,
                requiredEvidence,
                missingEvidence,
                requiresDbEvidence,
                true,
                unique(warnings)
        );
    }

    private List<FixPlanCandidate> candidates(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            SourceCandidateFlowChainItem target,
            String combinedSignals,
            boolean requiresDbEvidence,
            boolean hasDbEvidence,
            List<String> warnings,
            int maxCandidates
    ) {
        List<FixPlanCandidate> candidates = new ArrayList<>();
        if (target == null) {
            return candidates;
        }

        addCandidate(
                candidates,
                primaryRule(combinedSignals),
                target,
                sourceAnalysis,
                requiresDbEvidence,
                hasDbEvidence,
                "Primary deterministic source candidate from suspect change analysis.",
                warnings
        );

        if (containsAny(combinedSignals, "region", "preferredprovince")) {
            addCandidate(
                    candidates,
                    PatchRuleRegistry.MAPPING_FIX,
                    target,
                    sourceAnalysis,
                    requiresDbEvidence,
                    hasDbEvidence,
                    "Region/preferred province signal may require request-to-domain mapping review.",
                    warnings
            );
        }

        Optional<SourceCandidateFlowChainItem> dto = sourceAnalysis
                .candidateFlowChain()
                .stream()
                .filter(item -> "DTO".equalsIgnoreCase(item.layer()))
                .findFirst();
        if (dto.isPresent()
                && containsAny(combinedSignals, "preferredprovince", "taxinfo", "timezone")) {
            addCandidate(
                    candidates,
                    PatchRuleRegistry.REQUEST_FIELD_SANITIZATION,
                    dto.get(),
                    sourceAnalysis,
                    requiresDbEvidence,
                    hasDbEvidence,
                    "DTO fields related to region, tax or timezone require boundary validation.",
                    warnings
            );
        }

        if (requiresDbEvidence) {
            addCandidate(
                    candidates,
                    PatchRuleRegistry.DB_STATE_VALIDATION,
                    target,
                    sourceAnalysis,
                    true,
                    hasDbEvidence,
                    "Region/tax/timezone hypothesis needs application database state evidence before code changes.",
                    warnings
            );
        }

        return candidates.stream()
                .limit(maxCandidates)
                .toList();
    }

    private void addCandidate(
            List<FixPlanCandidate> candidates,
            String ruleId,
            SourceCandidateFlowChainItem target,
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            boolean requiresDbEvidence,
            boolean hasDbEvidence,
            String reason,
            List<String> warnings
    ) {
        PatchRuleReference rule = patchRuleRegistry
                .findById(ruleId)
                .orElseGet(() -> new PatchRuleReference(
                        "UNKNOWN",
                        "Unknown",
                        "No deterministic patch rule matched.",
                        List.of("UNKNOWN"),
                        List.of("SOURCE_REASONING"),
                        "HIGH",
                        true,
                        false
                ));
        candidates.add(new FixPlanCandidate(
                rule.ruleId(),
                safeString(target.file()),
                safeString(target.className()),
                safeString(target.methodName()),
                normalizedLayer(target.layer()),
                firstSignal(target.relatedSignals()),
                safeList(target.relatedSignals()),
                rule.ruleId(),
                rule.name(),
                reason + " Status remains HYPOTHESIS; no patch is generated.",
                rule.riskLevel(),
                candidateConfidence(sourceAnalysis, requiresDbEvidence, hasDbEvidence),
                "HYPOTHESIS",
                true,
                candidateEvidence(sourceAnalysis, target),
                candidateWarnings(warnings, sourceAnalysis)
        ));
    }

    private String primaryRule(String combinedSignals) {
        if (containsAny(combinedSignals, "region/update", "preferredprovince")) {
            return PatchRuleRegistry.VALIDATION_GUARD;
        }
        if (containsAny(combinedSignals, "taxinfo", "timezone")) {
            return PatchRuleRegistry.DB_STATE_VALIDATION;
        }
        return PatchRuleRegistry.VALIDATION_GUARD;
    }

    private Optional<SourceCandidateFlowChainItem> preferredTarget(
            List<SourceCandidateFlowChainItem> chain
    ) {
        return firstByLayer(chain, "SERVICE_IMPL")
                .or(() -> firstByLayer(chain, "SERVICE"))
                .or(() -> firstByLayer(chain, "VALIDATOR"))
                .or(() -> firstByLayer(chain, "CONTROLLER"))
                .or(() -> chain == null || chain.isEmpty()
                        ? Optional.empty()
                        : Optional.of(chain.get(0)));
    }

    private Optional<SourceCandidateFlowChainItem> firstByLayer(
            List<SourceCandidateFlowChainItem> chain,
            String layer
    ) {
        if (chain == null) {
            return Optional.empty();
        }
        return chain.stream()
                .filter(item -> layer.equalsIgnoreCase(item.layer()))
                .findFirst();
    }

    private List<FixPlanEvidenceReference> requiredEvidence(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            boolean requiresDbEvidence,
            Optional<EvidenceEntity> dbEvidence
    ) {
        List<FixPlanEvidenceReference> values = new ArrayList<>();
        values.add(new FixPlanEvidenceReference(
                "SOURCE_REASONING",
                "ReplayFix",
                sourceAnalysis.caseId().toString(),
                "HYPOTHESIS",
                "Deterministic source suspect analysis."
        ));
        if (requiresDbEvidence) {
            values.add(new FixPlanEvidenceReference(
                    APPLICATION_DB_EVIDENCE,
                    "Application database",
                    "",
                    "MISSING",
                    "Region/tax/timezone state evidence is required before patch approval."
            ));
        }
        dbEvidence.ifPresent(evidence -> values.add(new FixPlanEvidenceReference(
                APPLICATION_DB_EVIDENCE,
                safeString(evidence.getSource()),
                evidence.getId() == null ? "" : evidence.getId().toString(),
                "HYPOTHESIS",
                "Read-only application database evidence is available."
        )));
        return values;
    }

    private List<FixPlanEvidenceReference> candidateEvidence(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            SourceCandidateFlowChainItem target
    ) {
        List<FixPlanEvidenceReference> values = new ArrayList<>();
        values.add(new FixPlanEvidenceReference(
                "SOURCE_REASONING",
                "candidateFlowChain",
                safeString(target.file()),
                "HYPOTHESIS",
                safeString(target.reason())
        ));
        sourceAnalysis.lastCommitDiagnostics()
                .stream()
                .filter(diagnostic -> safeString(diagnostic.file())
                        .equals(safeString(target.file())))
                .findFirst()
                .ifPresent(diagnostic -> values.add(lastCommitEvidence(diagnostic)));
        return values;
    }

    private FixPlanEvidenceReference lastCommitEvidence(
            SourceLastCommitDiagnostic diagnostic
    ) {
        return new FixPlanEvidenceReference(
                "METHOD_COMMIT_HISTORY",
                "git",
                diagnostic.shortSha(),
                "DIAGNOSTIC",
                diagnostic.message()
        );
    }

    private List<String> candidateWarnings(
            List<String> warnings,
            SourceSuspectChangeAnalysisResponse sourceAnalysis
    ) {
        List<String> values = new ArrayList<>();
        if (sourceAnalysis.recentCommits().isEmpty()) {
            values.add(NO_RECENT_CHANGE_EVIDENCE);
        }
        if (warnings.contains(APPLICATION_DB_EVIDENCE)) {
            values.add(APPLICATION_DB_EVIDENCE);
        }
        return unique(values);
    }

    private double candidateConfidence(
            SourceSuspectChangeAnalysisResponse sourceAnalysis,
            boolean requiresDbEvidence,
            boolean hasDbEvidence
    ) {
        double value = 0.55;
        if (sourceAnalysis.recentCommits().isEmpty()) {
            value -= 0.15;
        }
        if (requiresDbEvidence) {
            value -= 0.10;
        }
        if (hasDbEvidence) {
            value += 0.05;
        }
        if (sourceAnalysis.candidateFlowChain().isEmpty()) {
            value -= 0.20;
        }
        return Math.max(0.0, Math.min(0.75, value));
    }

    private boolean requiresDatabaseEvidence(String combinedSignals) {
        return containsAny(
                combinedSignals,
                "region",
                "preferredprovince",
                "preferred province",
                "taxinfo",
                "tax_info",
                "timezone"
        );
    }

    private Optional<EvidenceEntity> applicationDbEvidence(UUID caseId) {
        try {
            List<EvidenceEntity> evidence = evidenceRepository.findByCaseId(caseId);
            if (evidence == null) {
                return Optional.empty();
            }
            return evidence.stream().filter(item -> {
                if (item.getEvidenceType() == EvidenceType.APPLICATION_DB_EVIDENCE) {
                    return true;
                }
                String haystack = (
                        safeString(item.getSource())
                                + " "
                                + safeString(item.getContentText())
                ).toLowerCase(Locale.ROOT);
                return haystack.contains("application_db_evidence")
                        || haystack.contains("db_evidence")
                        || haystack.contains("database evidence");
            }).findFirst();
        } catch (Exception exception) {
            log.warn(
                    "Fix plan DB evidence lookup failed for caseId={}",
                    caseId,
                    exception
            );
            return Optional.empty();
        }
    }

    private String combinedSignals(
            SourceSuspectChangeAnalysisResponse sourceAnalysis
    ) {
        StringBuilder builder = new StringBuilder();
        sourceAnalysis.matchedEndpointAnchors()
                .forEach(value -> builder.append(' ').append(value));
        sourceAnalysis.flowAnchors()
                .forEach(anchor -> builder.append(' ').append(anchor.value()));
        sourceAnalysis.candidateFlowChain()
                .forEach(item -> {
                    builder.append(' ').append(item.file());
                    builder.append(' ').append(item.className());
                    builder.append(' ').append(item.methodName());
                    safeList(item.relatedSignals())
                            .forEach(value -> builder.append(' ').append(value));
                });
        sourceAnalysis.candidateMethods()
                .forEach(method -> {
                    builder.append(' ').append(method.className());
                    builder.append(' ').append(method.methodName());
                    builder.append(' ').append(method.snippet());
                });
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private SourceSuspectChangeAnalysisResponse emptySourceAnalysis(
            ReplayCaseEntity replayCase
    ) {
        return new SourceSuspectChangeAnalysisResponse(
                replayCase.getId(),
                replayCase.getJiraKey(),
                "",
                firstNonBlank(replayCase.getSourceBranch(), "test2"),
                replayCase.getSourceCommit(),
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
                List.of(FIX_PLAN_SOURCE_ANALYSIS_FAILED),
                "DETERMINISTIC_ONLY",
                true
        );
    }

    private ReplayCaseEntity defaultCase(UUID caseId) {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("");
        replayCase.setSourceBranch("test2");
        replayCase.setSourceCommit("");
        return replayCase;
    }

    private String normalizedLayer(String layer) {
        String value = safeString(layer);
        if (value.isBlank()) {
            return "UNKNOWN";
        }
        return value;
    }

    private String firstSignal(List<String> signals) {
        if (signals == null || signals.isEmpty()) {
            return "";
        }
        return safeString(signals.get(0));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean containsAny(String value, String... needles) {
        String haystack = safeString(value).toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
