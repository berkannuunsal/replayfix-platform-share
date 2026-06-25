package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.PrOutcomeSummaryRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ReplayLabPrOutcomeSummaryService {

    public String buildSummary(UUID caseId, PrOutcomeSummaryRequest request) {
        PrOutcomeSummaryRequest safe = safe(request);
        return sanitize(String.join("\n",
                "## ReplayLab Summary",
                "",
                "### Defect",
                "",
                "- Defect: " + safe.defectKey(),
                "- Summary: " + safe.defectSummary(),
                "- ReplayLab case: " + caseId,
                "",
                "### Branch Flow",
                "",
                "- Source base branch: " + safe.sourceBaseBranch(),
                "- Bugfix branch: " + safe.bugfixBranch(),
                "- Target base branch: " + safe.targetBaseBranch(),
                "- Integration branch: " + safe.integrationBranch(),
                "- PR: " + safe.integrationBranch() + " -> " + safe.targetBaseBranch(),
                "",
                "### Applied Change",
                "",
                "- Change mode: " + safe.changeMode(),
                "- File: " + safe.filePath(),
                "- Bugfix commit: " + safe.bugfixCommitSha(),
                "- Integration commit: " + safe.integrationCommitSha(),
                "",
                "### PR Rule Preflight",
                "",
                "- Status: " + safe.reviewStatus(),
                "- Blocker violations: " + safe.blockerViolationCount(),
                "- Rules loaded:",
                rules(safe.rulesLoaded()),
                "",
                "### Guardrails",
                "",
                "ReplayLab did not:",
                "",
                "- push directly to the target branch",
                "- merge the PR",
                "- trigger Jenkins",
                "- trigger deployment",
                "- expose credentials or production payloads",
                "",
                "### Next Actions",
                "",
                "- Reviewer should inspect the generated change.",
                "- Do not merge until validation is complete.",
                "- Jenkins/build validation should be triggered only after human approval."
        ));
    }

    private String rules(List<String> rulesLoaded) {
        if (rulesLoaded == null || rulesLoaded.isEmpty()) {
            return "  - none";
        }
        return rulesLoaded.stream()
                .map(rule -> "  - " + rule)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("  - none");
    }

    private PrOutcomeSummaryRequest safe(PrOutcomeSummaryRequest request) {
        if (request == null) {
            return new PrOutcomeSummaryRequest(
                    "", "", "", "", "", "", "", "", "", "", "", "",
                    "", "", "", "SKIPPED", 0, List.of(), false, false
            );
        }
        return request;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)bearer[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)cookie[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)token[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)password[^\\s,;]*", "[redacted]")
                .replaceAll("(?i)secret[^\\s,;]*", "[redacted]")
                .replaceAll("https?://[^\\s]*@[^\\s]+", "[redacted-url]");
    }
}
