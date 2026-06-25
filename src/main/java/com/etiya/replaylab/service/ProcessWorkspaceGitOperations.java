package com.etiya.replaylab.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessWorkspaceGitOperations implements WorkspaceGitOperations {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Override
    public WorkspaceGitStatus status(Path workspaceRoot) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return new WorkspaceGitStatus(
                    false,
                    false,
                    "",
                    "",
                    "WORKSPACE_PATH_MISSING"
            );
        }
        if (!Files.isDirectory(workspaceRoot.resolve(".git"))) {
            return new WorkspaceGitStatus(
                    false,
                    false,
                    "",
                    "",
                    "WORKSPACE_GIT_REPOSITORY_MISSING"
            );
        }
        CommandResult status = run(workspaceRoot, "git", "status", "--porcelain");
        if (status.exitCode() != 0) {
            return new WorkspaceGitStatus(
                    true,
                    false,
                    "",
                    sanitize(status.output()),
                    "WORKSPACE_GIT_STATUS_FAILED"
            );
        }
        CommandResult head = run(workspaceRoot, "git", "rev-parse", "HEAD");
        return new WorkspaceGitStatus(
                true,
                !status.output().isBlank(),
                head.exitCode() == 0 ? head.output().trim() : "",
                sanitize(status.output()),
                ""
        );
    }

    @Override
    public WorkspaceGitPushResult pushApprovedChanges(
            Path workspaceRoot,
            String developmentBaseBranch,
            String environmentTargetBranch,
            String bugfixBranch,
            String integrationBranch,
            String commitMessage
    ) {
        List<String> warnings = new ArrayList<>();
        CommandResult fetch = run(workspaceRoot, "git", "fetch", "origin",
                developmentBaseBranch, environmentTargetBranch);
        if (fetch.exitCode() != 0) {
            return failure("WORKSPACE_GIT_FETCH_FAILED", fetch);
        }
        CommandResult checkoutBugfix = run(
                workspaceRoot,
                "git",
                "checkout",
                "-B",
                bugfixBranch,
                "origin/" + developmentBaseBranch
        );
        if (checkoutBugfix.exitCode() != 0) {
            return failure("WORKSPACE_GIT_BUGFIX_CHECKOUT_FAILED", checkoutBugfix);
        }
        CommandResult add = run(workspaceRoot, "git", "add", ".");
        if (add.exitCode() != 0) {
            return failure("WORKSPACE_GIT_ADD_FAILED", add);
        }
        CommandResult commit = run(
                workspaceRoot,
                "git",
                "commit",
                "-m",
                commitMessage
        );
        if (commit.exitCode() != 0
                && !commit.output().toLowerCase().contains("nothing to commit")) {
            return failure("WORKSPACE_GIT_COMMIT_FAILED", commit);
        }
        CommandResult commitShaResult = run(
                workspaceRoot,
                "git",
                "rev-parse",
                "HEAD"
        );
        String commitSha = commitShaResult.exitCode() == 0
                ? commitShaResult.output().trim()
                : "";
        CommandResult pushBugfix = run(
                workspaceRoot,
                "git",
                "push",
                "-u",
                "origin",
                bugfixBranch
        );
        if (pushBugfix.exitCode() != 0) {
            return failure("WORKSPACE_GIT_BUGFIX_PUSH_FAILED", pushBugfix);
        }
        CommandResult checkoutIntegration = run(
                workspaceRoot,
                "git",
                "checkout",
                "-B",
                integrationBranch,
                "origin/" + environmentTargetBranch
        );
        if (checkoutIntegration.exitCode() != 0) {
            return new WorkspaceGitPushResult(
                    true,
                    false,
                    false,
                    false,
                    false,
                    commitSha,
                    "",
                    "WORKSPACE_GIT_INTEGRATION_CHECKOUT_FAILED:"
                            + sanitize(checkoutIntegration.output())
            );
        }
        CommandResult merge = run(
                workspaceRoot,
                "git",
                "merge",
                "--no-ff",
                bugfixBranch,
                "-m",
                "ReplayLab merge " + bugfixBranch + " into " + integrationBranch
        );
        if (merge.exitCode() != 0) {
            boolean conflict = merge.output().toLowerCase().contains("conflict");
            return new WorkspaceGitPushResult(
                    true,
                    false,
                    true,
                    false,
                    conflict,
                    commitSha,
                    String.join(",", warnings),
                    conflict
                            ? "BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN"
                            : "WORKSPACE_GIT_INTEGRATION_MERGE_FAILED:"
                            + sanitize(merge.output())
            );
        }
        CommandResult pushIntegration = run(
                workspaceRoot,
                "git",
                "push",
                "-u",
                "origin",
                integrationBranch
        );
        if (pushIntegration.exitCode() != 0) {
            return new WorkspaceGitPushResult(
                    true,
                    false,
                    true,
                    true,
                    false,
                    commitSha,
                    "",
                    "WORKSPACE_GIT_INTEGRATION_PUSH_FAILED:"
                            + sanitize(pushIntegration.output())
            );
        }
        return new WorkspaceGitPushResult(
                true,
                true,
                true,
                true,
                false,
                commitSha,
                String.join(",", warnings),
                ""
        );
    }

    private WorkspaceGitPushResult failure(String code, CommandResult result) {
        return new WorkspaceGitPushResult(
                false,
                false,
                false,
                false,
                false,
                "",
                "",
                code + ":" + sanitize(result.output())
        );
    }

    private CommandResult run(Path workspaceRoot, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(
                    TIMEOUT.toSeconds(),
                    TimeUnit.SECONDS
            );
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "COMMAND_TIMEOUT");
            }
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            return new CommandResult(process.exitValue(), output);
        } catch (Exception exception) {
            return new CommandResult(1, exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)authorization", "[redacted]")
                .replaceAll("(?i)cookie", "[redacted]")
                .replaceAll("(?i)password", "[redacted]")
                .replaceAll("(?i)token", "[redacted]")
                .replaceAll("(?i)secret", "[redacted]")
                .replaceAll("(?i)apikey", "[redacted]")
                .replaceAll("(?i)privatekey", "[redacted]");
    }

    private record CommandResult(int exitCode, String output) {
    }
}
