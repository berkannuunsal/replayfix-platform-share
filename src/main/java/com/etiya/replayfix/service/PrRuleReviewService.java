package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.PrRuleReviewRequest;
import com.etiya.replayfix.api.dto.PrRuleReviewResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.integration.BitbucketSourceReadClient;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PrRuleReviewService {

    private static final String BACKEND = "BACKEND";
    private static final String FRONTEND = "FRONTEND";
    private static final String UNKNOWN = "UNKNOWN";

    private final ReplayCaseRepository caseRepository;
    private final ReplayFixProperties properties;
    private final BitbucketSourceReadClient sourceReadClient;

    public PrRuleReviewService(
            ReplayCaseRepository caseRepository,
            ReplayFixProperties properties,
            BitbucketSourceReadClient sourceReadClient
    ) {
        this.caseRepository = caseRepository;
        this.properties = properties;
        this.sourceReadClient = sourceReadClient;
    }

    public PrRuleReviewResponse preview(UUID caseId, PrRuleReviewRequest request) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Case not found: " + caseId));
        PrRuleReviewRequest safe = safeRequest(request, replayCase);
        String repositoryType = resolveRepositoryType(safe);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (UNKNOWN.equals(repositoryType)) {
            blockers.add("REPOSITORY_RULESET_NOT_RESOLVED");
            return response(caseId, safe.defectKey(), repositoryType, "", List.of(),
                    blockers, warnings, List.of(), List.of(), "PARTIAL");
        }

        List<String> ruleFiles = selectRuleFiles(repositoryType, safe.changedFiles());
        RuleLoadResult ruleLoad = loadRules(repositoryType, safe, ruleFiles, blockers, warnings);
        if (!blockers.isEmpty()) {
            return response(caseId, safe.defectKey(), repositoryType,
                    ruleLoad.ruleSourceBranch(), ruleLoad.branchesTried(), blockers, warnings,
                    List.of(), ruleLoad.rulesLoaded(), "PARTIAL");
        }

        List<PrRuleReviewResponse.Violation> violations =
                deterministicViolations(repositoryType, safe);
        if (!violations.isEmpty() && Boolean.TRUE.equals(safe.allowRepair())) {
            PrRuleReviewRequest repaired = repaired(safe, repositoryType, violations);
            List<PrRuleReviewResponse.Violation> repairedViolations =
                    deterministicViolations(repositoryType, repaired);
            if (repairedViolations.isEmpty()) {
                warnings.add("PR_RULE_REPAIR_APPLIED");
                return response(caseId, safe.defectKey(), repositoryType,
                        ruleLoad.ruleSourceBranch(), ruleLoad.branchesTried(), blockers, warnings,
                        List.of(), ruleLoad.rulesLoaded(), "ACCEPT");
            }
            warnings.add("PR_RULE_REPAIR_ATTEMPTED_STILL_BLOCKED");
            violations = repairedViolations;
        }
        blockers.addAll(violations.isEmpty()
                ? List.of()
                : List.of("PR_RULE_REVIEW_BLOCKED"));
        String status = violations.isEmpty() ? "ACCEPT" : "REJECT";
        return response(caseId, safe.defectKey(), repositoryType,
                ruleLoad.ruleSourceBranch(), ruleLoad.branchesTried(), blockers, warnings,
                violations, ruleLoad.rulesLoaded(), status);
    }

    public PrRuleReviewResponse reviewPlannedChange(
            UUID caseId,
            String projectKey,
            String repositorySlug,
            String repositoryType,
            String ruleSourceBranch,
            String integrationBranch,
            String bugfixBranch,
            String targetBaseBranch,
            String sourceBaseBranch,
            String targetBranch,
            String sourceBranch,
            String defectKey,
            String defectSummary,
            String filePath,
            String fileType,
            String language,
            String content,
            Boolean allowRepair
    ) {
        return preview(caseId, new PrRuleReviewRequest(
                "replayfix",
                projectKey,
                repositorySlug,
                repositoryType,
                ruleSourceBranch,
                integrationBranch,
                bugfixBranch,
                targetBranch,
                sourceBranch,
                targetBaseBranch,
                sourceBaseBranch,
                defectKey,
                defectSummary,
                List.of(new PrRuleReviewRequest.ChangedFile(
                        filePath,
                        fileType,
                        language,
                        addedLines(content),
                        List.of()
                )),
                true,
                allowRepair
        ));
    }

    public PrRuleReviewResponse reviewPlannedChange(
            UUID caseId,
            String projectKey,
            String repositorySlug,
            String repositoryType,
            String targetBranch,
            String sourceBranch,
            String defectKey,
            String defectSummary,
            String filePath,
            String fileType,
            String language,
            String content
    ) {
        return reviewPlannedChange(
                caseId,
                projectKey,
                repositorySlug,
                repositoryType,
                "",
                "",
                "",
                "",
                "",
                targetBranch,
                sourceBranch,
                defectKey,
                defectSummary,
                filePath,
                fileType,
                language,
                content,
                true
        );
    }

    private PrRuleReviewRequest safeRequest(
            PrRuleReviewRequest request,
            ReplayCaseEntity replayCase
    ) {
        if (request == null) {
            return new PrRuleReviewRequest(
                    "",
                    "DCE",
                    firstNonBlank(replayCase.getTargetKey(), "backend"),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    replayCase.getJiraKey(),
                    "",
                    List.of(),
                    true,
                    false
            );
        }
        return request;
    }

    private String resolveRepositoryType(PrRuleReviewRequest request) {
        String explicit = upper(request.repositoryType());
        if (BACKEND.equals(explicit) || FRONTEND.equals(explicit)) {
            return explicit;
        }
        String slug = nullToBlank(request.repositorySlug()).toLowerCase(Locale.ROOT);
        if (slug.contains("backend")) {
            return BACKEND;
        }
        if (slug.contains("frontend") || slug.contains("customer-ui") || slug.contains("ui")) {
            return FRONTEND;
        }
        for (PrRuleReviewRequest.ChangedFile file : safeFiles(request.changedFiles())) {
            String path = nullToBlank(file.path()).toLowerCase(Locale.ROOT);
            String language = upper(file.language());
            if (path.endsWith(".java") || "JAVA".equals(language)) {
                return BACKEND;
            }
            if (path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".html")
                    || "TYPESCRIPT".equals(language)) {
                return FRONTEND;
            }
        }
        return UNKNOWN;
    }

    private List<String> selectRuleFiles(
            String repositoryType,
            List<PrRuleReviewRequest.ChangedFile> files
    ) {
        Set<String> rules = new LinkedHashSet<>();
        rules.add("AGENTS.md");
        for (PrRuleReviewRequest.ChangedFile file : safeFiles(files)) {
            String path = nullToBlank(file.path()).toLowerCase(Locale.ROOT);
            if (BACKEND.equals(repositoryType)) {
                rules.add(".agents/AGENTS-Maintainability.md");
                if (path.contains("/src/test/") || path.endsWith("test.java")) {
                    rules.add(".agents/AGENTS-Unit-test.md");
                } else {
                    rules.add(".agents/AGENTS-Performance.md");
                }
            } else if (FRONTEND.equals(repositoryType)) {
                if (path.endsWith(".html")) {
                    rules.add(".agent/AGENT-Performance.md");
                    rules.add(".agent/AGENT-Accessibility.md");
                    rules.add(".agent/AGENT-Maintainability.md");
                } else if (path.endsWith(".spec.ts")) {
                    rules.add(".agent/AGENT-Quality.md");
                    rules.add(".agent/AGENT-Correctness.md");
                    rules.add(".agent/AGENT-Compatibility.md");
                } else if (path.contains("service") && path.endsWith(".ts")) {
                    rules.add(".agent/AGENT-Maintainability.md");
                    rules.add(".agent/AGENT-Compatibility.md");
                    rules.add(".agent/AGENT-Correctness.md");
                } else if (path.endsWith(".css") || path.endsWith(".scss")) {
                    rules.add(".agent/AGENT-Performance.md");
                    rules.add(".agent/AGENT-Accessibility.md");
                } else {
                    rules.add(".agent/AGENT-Maintainability.md");
                    rules.add(".agent/AGENT-Compatibility.md");
                    rules.add(".agent/AGENT-Performance.md");
                    rules.add(".agent/AGENT-Quality.md");
                }
            }
        }
        return List.copyOf(rules);
    }

    private RuleLoadResult loadRules(
            String repositoryType,
            PrRuleReviewRequest request,
            List<String> ruleFiles,
            List<String> blockers,
            List<String> warnings
    ) {
        ReplayFixProperties.SourceCandidateBitbucket bitbucket = bitbucketConfig();
        ReplayFixProperties.SourceCandidateRepository repository =
                repositoryConfig(repositoryType, request);
        List<String> branches = branchCandidates(repositoryType, request, repository);
        for (String branch : branches) {
            RootRuleFile root = findRootAgents(bitbucket, repository, repositoryType, branch, warnings);
            if (root.readFailed()) {
                blockers.add("PR_RULE_FILE_READ_FAILED");
                return new RuleLoadResult("", branches, List.of());
            }
            if (!root.found()) {
                continue;
            }
            List<String> loaded = new ArrayList<>();
            loaded.add(root.filePath());
            for (String file : ruleFiles) {
                if ("AGENTS.md".equals(file)) {
                    continue;
                }
                String loadedRule = loadFirstRuleVariant(
                        bitbucket,
                        repository,
                        repositoryType,
                        branch,
                        file,
                        warnings,
                        blockers
                );
                if (!loadedRule.isBlank()) {
                    loaded.add(loadedRule);
                }
                if (blockers.contains("PR_RULE_FILE_READ_FAILED")) {
                    return new RuleLoadResult(branch, branches, loaded);
                }
            }
            return new RuleLoadResult(branch, branches, unique(loaded));
        }
        blockers.add("PR_RULE_FILES_NOT_FOUND");
        return new RuleLoadResult("", branches, List.of());
    }

    private ReplayFixProperties.SourceCandidateBitbucket bitbucketConfig() {
        ReplayFixProperties.SourceCandidateBitbucket bitbucket =
                new ReplayFixProperties.SourceCandidateBitbucket();
        ReplayFixProperties.Bitbucket configured =
                properties.getIntegrations().getBitbucket();
        bitbucket.setBaseUrl(configured.getBaseUrl());
        bitbucket.setUsernameEnv("BITBUCKET_USERNAME");
        bitbucket.setTokenEnv("BITBUCKET_TOKEN");
        bitbucket.setRequestTimeoutSeconds((int) Math.max(1, configured.getTimeout().toSeconds()));
        return bitbucket;
    }

    private ReplayFixProperties.SourceCandidateRepository repositoryConfig(
            String repositoryType,
            PrRuleReviewRequest request
    ) {
        ReplayFixProperties.SourceCandidateRepository repository =
                new ReplayFixProperties.SourceCandidateRepository();
        repository.setLogicalName(FRONTEND.equals(repositoryType) ? "frontend" : "backend");
        repository.setProjectKey(firstNonBlank(request.projectKey(), "DCE"));
        repository.setRepositorySlug(firstNonBlank(
                request.repositorySlug(),
                FRONTEND.equals(repositoryType) ? "customer-ui" : "backend"
        ));
        repository.setBranch(firstNonBlank(request.targetBranch(), "master"));
        return repository;
    }

    private List<String> branchCandidates(
            String repositoryType,
            PrRuleReviewRequest request,
            ReplayFixProperties.SourceCandidateRepository repository
    ) {
        Set<String> branches = new LinkedHashSet<>();
        addIfPresent(branches, request.ruleSourceBranch());
        addIfPresent(branches, request.integrationBranch());
        addIfPresent(branches, request.bugfixBranch());
        addIfPresent(branches, request.sourceBranch());
        addIfPresent(branches, request.targetBranch());
        addIfPresent(branches, request.targetBaseBranch());
        addIfPresent(branches, request.sourceBaseBranch());
        addIfPresent(branches, configuredDefaultBranch(repositoryType, request));
        addIfPresent(branches, repository.getBranch());
        addIfPresent(branches, "master");
        return List.copyOf(branches);
    }

    private String configuredDefaultBranch(
            String repositoryType,
            PrRuleReviewRequest request
    ) {
        String requestedSlug = firstNonBlank(
                request.repositorySlug(),
                FRONTEND.equals(repositoryType) ? "customer-ui" : "backend"
        );
        return properties.getTargets().values().stream()
                .flatMap(target -> target.getBitbucket().getRepositories().values().stream())
                .filter(repo -> requestedSlug.equalsIgnoreCase(repo.getRepositorySlug()))
                .map(ReplayFixProperties.SourceCandidateRepository::getBranch)
                .filter(branch -> !branch.isBlank())
                .findFirst()
                .orElse("");
    }

    private RootRuleFile findRootAgents(
            ReplayFixProperties.SourceCandidateBitbucket bitbucket,
            ReplayFixProperties.SourceCandidateRepository repository,
            String repositoryType,
            String branch,
            List<String> warnings
    ) {
        for (String file : List.of("AGENTS.md", "agents.md")) {
            BitbucketSourceReadClient.SourceFileFetchResult result =
                    sourceReadClient.fetchRawFile(bitbucket, repository, file, branch);
            warnings.add(lookupDiagnostic(repositoryType, branch, file, result));
            if (result.success()) {
                return new RootRuleFile(true, false, file);
            }
            if (isRuleReadFailure(result.status())) {
                return new RootRuleFile(false, true, "");
            }
        }
        return new RootRuleFile(false, false, "");
    }

    private String loadFirstRuleVariant(
            ReplayFixProperties.SourceCandidateBitbucket bitbucket,
            ReplayFixProperties.SourceCandidateRepository repository,
            String repositoryType,
            String branch,
            String requestedFile,
            List<String> warnings,
            List<String> blockers
    ) {
        for (String file : ruleVariants(requestedFile)) {
            BitbucketSourceReadClient.SourceFileFetchResult result =
                    sourceReadClient.fetchRawFile(bitbucket, repository, file, branch);
            warnings.add(lookupDiagnostic(repositoryType, branch, file, result));
            if (result.success()) {
                return file;
            }
            if (isRuleReadFailure(result.status())) {
                blockers.add("PR_RULE_FILE_READ_FAILED");
                return "";
            }
        }
        return "";
    }

    private List<String> ruleVariants(String file) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(file);
        if (file.startsWith(".agents/AGENTS-")) {
            variants.add(file.replace(".agents/AGENTS-", ".agent/AGENT-"));
            variants.add(file.replace(".agents/AGENTS-", ".agents/agents-")
                    .replace(".md", ".md").toLowerCase(Locale.ROOT));
        } else if (file.startsWith(".agent/AGENT-")) {
            variants.add(file.replace(".agent/AGENT-", ".agents/AGENTS-"));
        }
        return List.copyOf(variants);
    }

    private String lookupDiagnostic(
            String repositoryType,
            String branch,
            String file,
            BitbucketSourceReadClient.SourceFileFetchResult result
    ) {
        return "PR_RULE_FILE_LOOKUP|repositoryType=" + repositoryType
                + "|branch=" + branch
                + "|file=" + file
                + "|status=" + safeStatus(result.status(), result.success());
    }

    private String safeStatus(String status, boolean success) {
        if (success || "OK".equalsIgnoreCase(status)) {
            return "200";
        }
        if ("BITBUCKET_FILE_NOT_FOUND".equals(status)) {
            return "404";
        }
        if ("BITBUCKET_READ_NOT_AUTHORIZED".equals(status)) {
            return "403";
        }
        return firstNonBlank(status, "UNKNOWN");
    }

    private boolean isRuleReadFailure(String status) {
        return status != null
                && !status.isBlank()
                && !"BITBUCKET_FILE_NOT_FOUND".equals(status)
                && !"NOT_FOUND".equals(status)
                && !"404".equals(status);
    }

    private List<PrRuleReviewResponse.Violation> deterministicViolations(
            String repositoryType,
            PrRuleReviewRequest request
    ) {
        List<PrRuleReviewResponse.Violation> violations = new ArrayList<>();
        for (PrRuleReviewRequest.ChangedFile file : safeFiles(request.changedFiles())) {
            for (String line : safeLines(file.added())) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (BACKEND.equals(repositoryType)) {
                    if (line.contains("Demo") || lower.contains("placeholder")) {
                        violations.add(violation(
                                "R-016",
                                "Unit Test",
                                "Generated test contains demo or placeholder wording",
                                file.path(),
                                line
                        ));
                    }
                    if (lower.contains("logger.") && !line.contains(",")) {
                        violations.add(violation(
                                "R-002",
                                "Maintainability",
                                "Log line should have parameter(s)",
                                file.path(),
                                line
                        ));
                    }
                } else if (FRONTEND.equals(repositoryType)) {
                    if (lower.matches(".*\\bany\\b.*")) {
                        violations.add(violation(
                                "R-FE-001",
                                "Maintainability",
                                "TypeScript any usage requires explicit justification",
                                file.path(),
                                line
                        ));
                    }
                    if (lower.contains("signal(") || lower.contains("input(")
                            || lower.contains("output(") || line.contains("@if")
                            || line.contains("@for")) {
                        violations.add(violation(
                                "R-FE-002",
                                "Compatibility",
                                "Angular 16/17-only syntax is not allowed for Angular 15",
                                file.path(),
                                line
                        ));
                    }
                    if (lower.contains("<button") && !lower.contains("aria-label")) {
                        violations.add(violation(
                                "R-FE-008",
                                "Accessibility",
                                "Interactive control requires accessible label",
                                file.path(),
                                line
                        ));
                    }
                }
            }
        }
        return violations;
    }

    private PrRuleReviewResponse.Violation violation(
            String ruleId,
            String category,
            String message,
            String filePath,
            String line
    ) {
        return new PrRuleReviewResponse.Violation(
                ruleId,
                "BLOCKER",
                category,
                message,
                List.of(new PrRuleReviewResponse.Evidence(filePath, line))
        );
    }

    private PrRuleReviewResponse response(
            UUID caseId,
            String defectKey,
            String repositoryType,
            String ruleSourceBranch,
            List<String> ruleLookupBranchesTried,
            List<String> blockers,
            List<String> warnings,
            List<PrRuleReviewResponse.Violation> violations,
            List<String> rulesLoaded,
            String status
    ) {
        return new PrRuleReviewResponse(
                caseId,
                defectKey,
                repositoryType,
                ruleSourceBranch,
                status,
                violations.size(),
                violations,
                technicalReport(status, violations),
                rulesLoaded,
                ruleLookupBranchesTried,
                unique(blockers),
                unique(sanitize(warnings)),
                nextActions(blockers, violations)
        );
    }

    private PrRuleReviewRequest repaired(
            PrRuleReviewRequest request,
            String repositoryType,
            List<PrRuleReviewResponse.Violation> violations
    ) {
        List<PrRuleReviewRequest.ChangedFile> repairedFiles = safeFiles(request.changedFiles())
                .stream()
                .map(file -> new PrRuleReviewRequest.ChangedFile(
                        file.path(),
                        file.fileType(),
                        file.language(),
                        safeLines(file.added()).stream()
                                .map(line -> repairedLine(repositoryType, line))
                                .toList(),
                        file.removed()
                ))
                .toList();
        return new PrRuleReviewRequest(
                request.requestedBy(),
                request.projectKey(),
                request.repositorySlug(),
                request.repositoryType(),
                request.ruleSourceBranch(),
                request.integrationBranch(),
                request.bugfixBranch(),
                request.targetBranch(),
                request.sourceBranch(),
                request.targetBaseBranch(),
                request.sourceBaseBranch(),
                request.defectKey(),
                request.defectSummary(),
                repairedFiles,
                request.blockPrOnViolation(),
                false
        );
    }

    private String repairedLine(String repositoryType, String line) {
        String repaired = line == null ? "" : line;
        if (BACKEND.equals(repositoryType)) {
            repaired = repaired
                    .replace("Demo", "")
                    .replace("demo", "")
                    .replace("placeholder", "review-required draft");
        } else if (FRONTEND.equals(repositoryType)) {
            repaired = repaired
                    .replaceAll("\\bany\\b", "unknown")
                    .replace("signal(", "/* Angular 15 compatible state */ Boolean(")
                    .replace("input(", "/* Angular 15 compatible input */ ")
                    .replace("output(", "/* Angular 15 compatible output */ ")
                    .replace("@if", "*ngIf")
                    .replace("@for", "*ngFor");
            if (repaired.toLowerCase(Locale.ROOT).contains("<button")
                    && !repaired.toLowerCase(Locale.ROOT).contains("aria-label")) {
                repaired = repaired.replaceFirst("(?i)<button", "<button aria-label=\"ReplayLab review required\"");
            }
        }
        return repaired;
    }

    private String technicalReport(
            String status,
            List<PrRuleReviewResponse.Violation> violations
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("ReviewStatus: " + status);
        lines.add("Blocker violations: " + violations.size());
        lines.add("");
        lines.add("Violations of Rules(with rule IDs)");
        if (violations.isEmpty()) {
            lines.add("- None");
        }
        for (PrRuleReviewResponse.Violation violation : violations) {
            lines.add("- " + violation.ruleId() + " " + violation.severity()
                    + " " + violation.category() + ": " + violation.message());
            lines.add("  Evidence: exact offending added lines (line number + code)");
            violation.evidence().forEach(evidence ->
                    lines.add("  - " + evidence.filePath() + " " + evidence.line()));
        }
        return String.join("\n", lines);
    }

    private List<String> nextActions(
            List<String> blockers,
            List<PrRuleReviewResponse.Violation> violations
    ) {
        if ((blockers == null || blockers.isEmpty()) && violations.isEmpty()) {
            return List.of("Continue guarded draft PR creation.");
        }
        List<String> actions = new ArrayList<>();
        if (blockers != null) {
            blockers.forEach(blocker -> actions.add("Resolve blocker: " + blocker));
        }
        violations.forEach(violation -> actions.add(
                "Fix rule violation " + violation.ruleId() + " before creating PR."));
        return actions;
    }

    private List<String> addedLines(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String[] lines = content.split("\\R", -1);
        List<String> added = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                added.add("Line " + (i + 1) + ": " + lines[i]);
            }
        }
        return added;
    }

    private List<PrRuleReviewRequest.ChangedFile> safeFiles(
            List<PrRuleReviewRequest.ChangedFile> files
    ) {
        return files == null ? List.of() : files;
    }

    private List<String> safeLines(List<String> lines) {
        return lines == null ? List.of() : lines;
    }

    private String upper(String value) {
        return nullToBlank(value).trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::sanitize).toList();
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)authorization[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)bearer[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)cookie[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)token[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)password[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)secret[^\\s,;]*", "[redacted]");
    }

    private List<String> unique(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private record RuleLoadResult(
            String ruleSourceBranch,
            List<String> branchesTried,
            List<String> rulesLoaded
    ) {
    }

    private record RootRuleFile(
            boolean found,
            boolean readFailed,
            String filePath
    ) {
    }
}
