package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.PrRuleReviewRequest;
import com.etiya.replayfix.api.dto.PrRuleReviewResponse;
import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.domain.ReplayCaseStatus;
import com.etiya.replayfix.integration.BitbucketSourceReadClient;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrRuleReviewServiceTest {

    private UUID caseId;
    private ReplayCaseRepository caseRepository;
    private BitbucketSourceReadClient sourceReadClient;
    private PrRuleReviewService service;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        caseRepository = mock(ReplayCaseRepository.class);
        sourceReadClient = mock(BitbucketSourceReadClient.class);
        ReplayFixProperties properties = new ReplayFixProperties();
        service = new PrRuleReviewService(caseRepository, properties, sourceReadClient);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity()));
        when(sourceReadClient.fetchRawFile(any(), any(), any(), any()))
                .thenAnswer(invocation -> loadedRule(invocation.getArgument(2)));
    }

    @Test
    void selectsBackendMaintainabilityAndUnitTestRulesForJavaTestDiff() {
        PrRuleReviewResponse response = service.preview(caseId, request(
                "backend",
                "BACKEND",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "JAVA",
                List.of("Line 1: package com.etiya.replayfix.generated;")
        ));

        assertThat(response.repositoryType()).isEqualTo("BACKEND");
        assertThat(response.reviewStatus()).isEqualTo("ACCEPT");
        assertThat(response.rulesLoaded())
                .contains("AGENTS.md",
                        ".agents/AGENTS-Maintainability.md",
                        ".agents/AGENTS-Unit-test.md")
                .doesNotContain(".agents/AGENTS-Performance.md");
        assertThat(response.technicalReviewReport())
                .contains("Violations of Rules(with rule IDs)")
                .contains("Blocker violations: 0");
    }

    @Test
    void selectsBackendMaintainabilityAndPerformanceRulesForJavaSourceDiff() {
        PrRuleReviewResponse response = service.preview(caseId, request(
                "backend",
                "BACKEND",
                "ControllerBackend/src/main/java/com/etiya/UserService.java",
                "JAVA",
                List.of("Line 42: return user;")
        ));

        assertThat(response.rulesLoaded())
                .contains("AGENTS.md",
                        ".agents/AGENTS-Maintainability.md",
                        ".agents/AGENTS-Performance.md")
                .doesNotContain(".agents/AGENTS-Unit-test.md");
    }

    @Test
    void detectsFrontendRepositoryAndBlocksAngular16SyntaxForAngular15() {
        PrRuleReviewResponse response = service.preview(caseId, request(
                "frontend-customer-ui",
                "FRONTEND",
                "src/app/customer/customer.component.ts",
                "TYPESCRIPT",
                List.of("Line 7: readonly state = signal(true);")
        ));

        assertThat(response.repositoryType()).isEqualTo("FRONTEND");
        assertThat(response.reviewStatus()).isEqualTo("REJECT");
        assertThat(response.blockers()).contains("PR_RULE_REVIEW_BLOCKED");
        assertThat(response.violations())
                .extracting(PrRuleReviewResponse.Violation::ruleId)
                .contains("R-FE-002");
        assertThat(response.rulesLoaded())
                .contains("AGENTS.md", ".agent/AGENT-Compatibility.md");
    }

    @Test
    void selectsFrontendAccessibilityRulesForTemplateDiff() {
        PrRuleReviewResponse response = service.preview(caseId, request(
                "frontend-customer-ui",
                "",
                "src/app/customer/customer.component.html",
                "HTML",
                List.of("Line 9: <button (click)=\"save()\"></button>")
        ));

        assertThat(response.repositoryType()).isEqualTo("FRONTEND");
        assertThat(response.rulesLoaded())
                .contains("AGENTS.md",
                        ".agent/AGENT-Performance.md",
                        ".agent/AGENT-Accessibility.md",
                        ".agent/AGENT-Maintainability.md");
        assertThat(response.violations())
                .extracting(PrRuleReviewResponse.Violation::ruleId)
                .contains("R-FE-008");
    }

    @Test
    void backendDeterministicBlockerIncludesExactAddedLineEvidence() {
        PrRuleReviewResponse response = service.preview(caseId, request(
                "backend",
                "",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "JAVA",
                List.of("Line 12: class FIZZMS10228DemoRegressionTest {")
        ));

        assertThat(response.reviewStatus()).isEqualTo("REJECT");
        assertThat(response.violations()).hasSize(1);
        assertThat(response.violations().get(0).ruleId()).isEqualTo("R-016");
        assertThat(response.violations().get(0).evidence().get(0).line())
                .isEqualTo("Line 12: class FIZZMS10228DemoRegressionTest {");
        assertThat(response.technicalReviewReport())
                .contains("Evidence: exact offending added lines (line number + code)");
    }

    @Test
    void unknownRepositoryTypeBlocksByDefault() {
        PrRuleReviewResponse response = service.preview(caseId, request(
                "unknown-repo",
                "",
                "README.md",
                "MARKDOWN",
                List.of("Line 1: safe")
        ));

        assertThat(response.reviewStatus()).isEqualTo("PARTIAL");
        assertThat(response.blockers()).contains("REPOSITORY_RULESET_NOT_RESOLVED");
    }

    @Test
    void missingAgentsFileBlocksPrRuleReview() {
        mockAvailableRules(Set.of());

        PrRuleReviewResponse response = service.preview(caseId, request(
                "backend",
                "BACKEND",
                "ControllerBackend/src/main/java/com/etiya/UserService.java",
                "JAVA",
                List.of("Line 42: return user;")
        ));

        assertThat(response.reviewStatus()).isEqualTo("PARTIAL");
        assertThat(response.blockers()).contains("PR_RULE_FILES_NOT_FOUND");
    }

    @Test
    void backendAgentsCanBeLoadedFromIntegrationBranch() {
        mockAvailableRules(Set.of(
                "Integration/test2/FIZZMS-10228|AGENTS.md",
                "Integration/test2/FIZZMS-10228|.agents/AGENTS-Maintainability.md",
                "Integration/test2/FIZZMS-10228|.agents/AGENTS-Unit-test.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "feature/source",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "JAVA",
                List.of("Line 1: package com.etiya.replayfix.generated;"),
                false
        ));

        assertThat(response.reviewStatus()).isEqualTo("ACCEPT");
        assertThat(response.ruleSourceBranch()).isEqualTo("Integration/test2/FIZZMS-10228");
        assertThat(response.ruleLookupBranchesTried())
                .containsSequence("Integration/test2/FIZZMS-10228", "bugfix/FIZZMS-10228");
        assertThat(response.rulesLoaded())
                .contains("AGENTS.md",
                        ".agents/AGENTS-Maintainability.md",
                        ".agents/AGENTS-Unit-test.md");
        assertThat(response.blockers()).doesNotContain("PR_RULE_FILES_NOT_FOUND");
    }

    @Test
    void backendAgentsCanBeLoadedFromBugfixBranchWhenIntegrationMissing() {
        mockAvailableRules(Set.of(
                "bugfix/FIZZMS-10228|AGENTS.md",
                "bugfix/FIZZMS-10228|.agents/AGENTS-Maintainability.md",
                "bugfix/FIZZMS-10228|.agents/AGENTS-Unit-test.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "feature/source",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "JAVA",
                List.of("Line 1: package com.etiya.replayfix.generated;"),
                false
        ));

        assertThat(response.ruleSourceBranch()).isEqualTo("bugfix/FIZZMS-10228");
        assertThat(response.rulesLoaded()).contains(".agents/AGENTS-Unit-test.md");
    }

    @Test
    void backendAgentsCanBeLoadedFromTargetBaseBranch() {
        mockAvailableRules(Set.of(
                "Integration/test2/FIZZMS-6686|AGENTS.md",
                "Integration/test2/FIZZMS-6686|.agents/AGENTS-Maintainability.md",
                "Integration/test2/FIZZMS-6686|.agents/AGENTS-Performance.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "",
                "ControllerBackend/src/main/java/com/etiya/UserService.java",
                "JAVA",
                List.of("Line 42: return user;"),
                false
        ));

        assertThat(response.ruleSourceBranch()).isEqualTo("Integration/test2/FIZZMS-6686");
        assertThat(response.rulesLoaded()).contains(".agents/AGENTS-Performance.md");
    }

    @Test
    void explicitRuleSourceBranchIsTriedFirst() {
        mockAvailableRules(Set.of(
                "rules/branch|AGENTS.md",
                "rules/branch|.agents/AGENTS-Maintainability.md",
                "rules/branch|.agents/AGENTS-Unit-test.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "master",
                "target/custom",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "rules/branch",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "JAVA",
                List.of("Line 1: package com.etiya.replayfix.generated;"),
                false
        ));

        assertThat(response.ruleLookupBranchesTried().get(0)).isEqualTo("rules/branch");
        assertThat(response.ruleSourceBranch()).isEqualTo("rules/branch");
    }

    @Test
    void frontendAgentsCanBeLoadedFromIntegrationBranch() {
        mockAvailableRules(Set.of(
                "integration/test1/FIZZ-28501|AGENTS.md",
                "integration/test1/FIZZ-28501|.agent/AGENT-Maintainability.md",
                "integration/test1/FIZZ-28501|.agent/AGENT-Compatibility.md",
                "integration/test1/FIZZ-28501|.agent/AGENT-Performance.md",
                "integration/test1/FIZZ-28501|.agent/AGENT-Quality.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "customer-ui",
                "FRONTEND",
                "master",
                "integration/test1/FIZZ-28500",
                "bugfix/FIZZ-28501",
                "integration/test1/FIZZ-28501",
                "",
                "src/app/customer/customer.component.ts",
                "TYPESCRIPT",
                List.of("Line 7: const selected = true;"),
                false
        ));

        assertThat(response.repositoryType()).isEqualTo("FRONTEND");
        assertThat(response.ruleSourceBranch()).isEqualTo("integration/test1/FIZZ-28501");
        assertThat(response.rulesLoaded()).contains(".agent/AGENT-Compatibility.md");
    }

    @Test
    void frontendRuleLookupFallsBackToAgentsFolderVariant() {
        mockAvailableRules(Set.of(
                "feature/frontend|AGENTS.md",
                "feature/frontend|.agents/AGENTS-Maintainability.md",
                "feature/frontend|.agents/AGENTS-Compatibility.md",
                "feature/frontend|.agents/AGENTS-Performance.md",
                "feature/frontend|.agents/AGENTS-Quality.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "customer-ui",
                "FRONTEND",
                "master",
                "target/frontend",
                "bugfix/FIZZ-28501",
                "feature/frontend",
                "",
                "src/app/customer/customer.component.ts",
                "TYPESCRIPT",
                List.of("Line 7: const selected = true;"),
                false
        ));

        assertThat(response.rulesLoaded())
                .contains(".agents/AGENTS-Maintainability.md",
                        ".agents/AGENTS-Compatibility.md");
    }

    @Test
    void backendRuleLookupFallsBackToAgentFolderVariant() {
        mockAvailableRules(Set.of(
                "feature/backend|AGENTS.md",
                "feature/backend|.agent/AGENT-Maintainability.md",
                "feature/backend|.agent/AGENT-Unit-test.md"
        ));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "master",
                "target/backend",
                "bugfix/FIZZMS-10228",
                "feature/backend",
                "",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/FIZZMS10228RegressionTest.java",
                "JAVA",
                List.of("Line 1: package com.etiya.replayfix.generated;"),
                false
        ));

        assertThat(response.rulesLoaded())
                .contains(".agent/AGENT-Maintainability.md",
                        ".agent/AGENT-Unit-test.md");
    }

    @Test
    void missingAllCandidateBranchesReturnsRuleFilesNotFound() {
        mockAvailableRules(Set.of());

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "source/custom",
                "target/custom",
                "bugfix/CRM-123",
                "Integration/custom/CRM-123",
                "",
                "ControllerBackend/src/main/java/com/etiya/UserService.java",
                "JAVA",
                List.of("Line 42: return user;"),
                false
        ));

        assertThat(response.reviewStatus()).isEqualTo("PARTIAL");
        assertThat(response.blockers()).contains("PR_RULE_FILES_NOT_FOUND");
        assertThat(response.ruleLookupBranchesTried())
                .contains("Integration/custom/CRM-123", "bugfix/CRM-123",
                        "source/custom", "target/custom", "master")
                .doesNotContain("test1", "test2");
    }

    @Test
    void diagnosticsContainOnlyBranchFileAndStatus() {
        mockAvailableRules(Set.of("feature/with/slash|AGENTS.md"));

        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "master",
                "target/custom",
                "bugfix/CRM-123",
                "feature/with/slash",
                "",
                "ControllerBackend/src/main/java/com/etiya/UserService.java",
                "JAVA",
                List.of("Line 42: return user;"),
                false
        ));

        assertThat(response.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("PR_RULE_FILE_LOOKUP")
                        .contains("branch=feature/with/slash")
                        .contains("file=AGENTS.md")
                        .contains("status=200"));
        assertThat(response.toString().toLowerCase())
                .doesNotContain("authorization")
                .doesNotContain("token")
                .doesNotContain("secret");
    }

    @Test
    void violationsCanBeRepairedAndReviewedAgainWhenAllowed() {
        PrRuleReviewResponse response = service.preview(caseId, requestWithBranches(
                "backend",
                "BACKEND",
                "master",
                "target/custom",
                "bugfix/CRM-123",
                "feature/rules",
                "",
                "ControllerBackend/src/test/java/com/etiya/replayfix/generated/CRM123RegressionTest.java",
                "JAVA",
                List.of("Line 12: class CRM123DemoRegressionTest {"),
                true
        ));

        assertThat(response.reviewStatus()).isEqualTo("ACCEPT");
        assertThat(response.blockerViolationCount()).isZero();
        assertThat(response.warnings()).contains("PR_RULE_REPAIR_APPLIED");
    }

    @Test
    void warningsAreSanitized() {
        when(sourceReadClient.fetchRawFile(any(), any(), eq(".agents/AGENTS-Maintainability.md"), any()))
                .thenReturn(new BitbucketSourceReadClient.SourceFileFetchResult(
                        false,
                        "Authorization bearer token secret password cookie",
                        "",
                        "backend",
                        "DCE",
                        "backend",
                        "master",
                        ".agents/AGENTS-Maintainability.md",
                        List.of()));

        PrRuleReviewResponse response = service.preview(caseId, request(
                "backend",
                "BACKEND",
                "ControllerBackend/src/main/java/com/etiya/UserService.java",
                "JAVA",
                List.of("Line 42: return user;")
        ));

        assertThat(response.toString().toLowerCase())
                .doesNotContain("bearer token")
                .doesNotContain(" secret ")
                .doesNotContain(" password ")
                .doesNotContain(" cookie ");
    }

    private PrRuleReviewRequest request(
            String repositorySlug,
            String repositoryType,
            String path,
            String language,
            List<String> added
    ) {
        return new PrRuleReviewRequest(
                "berkan",
                "DCE",
                repositorySlug,
                repositoryType,
                "Integration/test2/FIZZMS-6686",
                "Integration/test2/FIZZMS-10228",
                "FIZZMS-10228",
                "Safe summary",
                List.of(new PrRuleReviewRequest.ChangedFile(
                        path,
                        "REGRESSION_TEST",
                        language,
                        added,
                        List.of()
                )),
                true
        );
    }

    private PrRuleReviewRequest requestWithBranches(
            String repositorySlug,
            String repositoryType,
            String sourceBaseBranch,
            String targetBaseBranch,
            String bugfixBranch,
            String integrationBranch,
            String ruleSourceBranch,
            String path,
            String language,
            List<String> added,
            boolean allowRepair
    ) {
        return new PrRuleReviewRequest(
                "berkan",
                "DCE",
                repositorySlug,
                repositoryType,
                ruleSourceBranch,
                integrationBranch,
                bugfixBranch,
                targetBaseBranch,
                integrationBranch,
                targetBaseBranch,
                sourceBaseBranch,
                "FIZZMS-10228",
                "Safe summary",
                List.of(new PrRuleReviewRequest.ChangedFile(
                        path,
                        "REGRESSION_TEST",
                        language,
                        added,
                        List.of()
                )),
                true,
                allowRepair
        );
    }

    private void mockAvailableRules(Set<String> branchAndFilePairs) {
        when(sourceReadClient.fetchRawFile(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String file = invocation.getArgument(2);
                    String branch = invocation.getArgument(3);
                    if (branchAndFilePairs.contains(branch + "|" + file)) {
                        return new BitbucketSourceReadClient.SourceFileFetchResult(
                                true,
                                "OK",
                                "safe rule content for " + file,
                                "backend",
                                "DCE",
                                "backend",
                                branch,
                                file,
                                List.of()
                        );
                    }
                    return new BitbucketSourceReadClient.SourceFileFetchResult(
                            false,
                            "BITBUCKET_FILE_NOT_FOUND",
                            "",
                            "backend",
                            "DCE",
                            "backend",
                            branch,
                            file,
                            List.of()
                    );
                });
    }

    private BitbucketSourceReadClient.SourceFileFetchResult loadedRule(String path) {
        return new BitbucketSourceReadClient.SourceFileFetchResult(
                true,
                "OK",
                "safe rule content for " + path,
                "backend",
                "DCE",
                "backend",
                "master",
                path,
                List.of()
        );
    }

    private ReplayCaseEntity caseEntity() {
        ReplayCaseEntity entity = new ReplayCaseEntity();
        entity.setId(caseId);
        entity.setJiraKey("FIZZMS-10228");
        entity.setTargetKey("backend");
        entity.setStatus(ReplayCaseStatus.NEW);
        return entity;
    }
}
