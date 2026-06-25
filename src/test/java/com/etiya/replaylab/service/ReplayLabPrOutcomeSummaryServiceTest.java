package com.etiya.replaylab.service;

import com.etiya.replaylab.api.dto.PrOutcomeSummaryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayLabPrOutcomeSummaryServiceTest {

    private final ReplayLabPrOutcomeSummaryService service =
            new ReplayLabPrOutcomeSummaryService();

    @Test
    void buildsSummaryWithDefectBranchFlowAppliedChangePreflightAndGuardrails() {
        UUID caseId = UUID.randomUUID();

        String summary = service.buildSummary(caseId, request());

        assertThat(summary)
                .contains("## ReplayLab Summary")
                .contains("- Defect: FIZZMS-10228")
                .contains("- Summary: FMS-170772//Region mismatch")
                .contains("- ReplayLab case: " + caseId)
                .contains("- Source base branch: master")
                .contains("- Bugfix branch: bugfix/FIZZMS-10228")
                .contains("- Target base branch: Integration/test2/FIZZMS-6686")
                .contains("- Integration branch: Integration/test2/FIZZMS-10228")
                .contains("- File: ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228RegressionTest.java")
                .contains("- Status: ACCEPT")
                .contains("  - AGENTS.md")
                .contains("ReplayLab did not:")
                .contains("- merge the PR")
                .contains("- trigger Jenkins");
    }

    @Test
    void summaryDoesNotExposeTokenOrAuthorizationHeader() {
        PrOutcomeSummaryRequest unsafe = new PrOutcomeSummaryRequest(
                "berkan",
                "DCE",
                "backend",
                "11397",
                "https://token@bitbucket/pr/11397",
                "FIZZMS-10228",
                "Authorization Bearer token secret",
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "TARGETED_TEST_CHANGE",
                "ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228RegressionTest.java",
                "bugfixCommit",
                "integrationCommit",
                "ACCEPT",
                0,
                List.of("AGENTS.md"),
                false,
                false
        );

        assertThat(service.buildSummary(UUID.randomUUID(), unsafe).toLowerCase())
                .doesNotContain("authorization")
                .doesNotContain("bearer")
                .doesNotContain(" token ")
                .doesNotContain(" secret");
    }

    private PrOutcomeSummaryRequest request() {
        return new PrOutcomeSummaryRequest(
                "berkan",
                "DCE",
                "backend",
                "11397",
                "https://bitbucket/pr/11397",
                "FIZZMS-10228",
                "FMS-170772//Region mismatch",
                "master",
                "Integration/test2/FIZZMS-6686",
                "bugfix/FIZZMS-10228",
                "Integration/test2/FIZZMS-10228",
                "TARGETED_TEST_CHANGE",
                "ControllerBackend/src/test/java/com/etiya/replaylab/generated/FIZZMS10228RegressionTest.java",
                "49cf7a",
                "937df3",
                "ACCEPT",
                0,
                List.of("AGENTS.md", ".agents/AGENTS-Maintainability.md", ".agents/AGENTS-Unit-test.md"),
                false,
                false
        );
    }
}
