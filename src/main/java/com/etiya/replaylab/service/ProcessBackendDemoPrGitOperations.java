package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessBackendDemoPrGitOperations
        implements BackendDemoPrGitOperations {

    private static final Duration TIMEOUT = Duration.ofSeconds(240);

    private final ReplayLabProperties properties;

    public ProcessBackendDemoPrGitOperations(ReplayLabProperties properties) {
        this.properties = properties;
    }

    @Override
    public BackendDemoPrGitResult commitPushAndPrepareIntegrationBranch(
            String projectKey,
            String repositorySlug,
            String sourceBaseBranch,
            String targetBaseBranch,
            String bugfixBranch,
            String integrationBranch,
            boolean bugfixBranchExists,
            boolean integrationBranchExists,
            String generatedFilePath,
            String generatedFileContent,
            String commitMessage
    ) {
        Path root = null;
        Path askPassScript = null;
        try {
            String cloneUrl = cloneUrl(projectKey, repositorySlug);
            if (isBlank(cloneUrl)) {
                return failure("BITBUCKET_CLONE_URL_NOT_CONFIGURED", "");
            }
            root = Files.createTempDirectory("replaylab-backend-demo-pr-");
            askPassScript = createAskPassScript(root);
            Path repo = root.resolve(repositorySlug);
            CommandResult clone = run(root, askPassScript, gitExecutable(), "clone", cloneUrl, repositorySlug);
            if (clone.exitCode() != 0) {
                return failure("BITBUCKET_REPOSITORY_CLONE_FAILED", clone.output());
            }
            CommandResult fetch = run(repo, askPassScript, gitExecutable(), "fetch", "origin");
            if (fetch.exitCode() != 0) {
                return failure("BITBUCKET_FETCH_FAILED", fetch.output());
            }
            String bugfixStart = bugfixBranchExists
                    ? "origin/" + bugfixBranch
                    : "origin/" + sourceBaseBranch;
            CommandResult bugfixCheckout =
                    run(repo, askPassScript, gitExecutable(), "checkout", "-B", bugfixBranch, bugfixStart);
            if (bugfixCheckout.exitCode() != 0) {
                return failure("BITBUCKET_BUGFIX_BRANCH_CHECKOUT_FAILED",
                        bugfixCheckout.output());
            }
            Path generated = repo.resolve(generatedFilePath).normalize();
            if (!generated.startsWith(repo)) {
                return failure("PATH_OUTSIDE_TEMP_WORKSPACE_REJECTED", "");
            }
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, generatedFileContent, StandardCharsets.UTF_8);
            CommandResult add = run(repo, askPassScript, gitExecutable(), "add", generatedFilePath);
            if (add.exitCode() != 0) {
                return failure("BITBUCKET_DEMO_FILE_ADD_FAILED", add.output());
            }
            CommandResult commit = run(repo, askPassScript, gitExecutable(), "commit", "-m", commitMessage);
            if (commit.exitCode() != 0
                    && !commit.output().toLowerCase(Locale.ROOT).contains("nothing to commit")) {
                return failure("BITBUCKET_DEMO_FILE_COMMIT_FAILED", commit.output());
            }
            String bugfixCommitSha = head(repo, askPassScript);
            CommandResult pushBugfix = run(repo, askPassScript, gitExecutable(), "push", "-u", "origin", bugfixBranch);
            if (pushBugfix.exitCode() != 0) {
                return failure("BITBUCKET_BUGFIX_BRANCH_PUSH_FAILED", pushBugfix.output());
            }

            String integrationStart = integrationBranchExists
                    ? "origin/" + integrationBranch
                    : "origin/" + targetBaseBranch;
            CommandResult integrationCheckout =
                    run(repo, askPassScript, gitExecutable(), "checkout", "-B", integrationBranch, integrationStart);
            if (integrationCheckout.exitCode() != 0) {
                return failure("BITBUCKET_INTEGRATION_BRANCH_CHECKOUT_FAILED",
                        integrationCheckout.output());
            }
            CommandResult cherryPick = run(repo, askPassScript, gitExecutable(), "cherry-pick", "--no-edit", bugfixCommitSha);
            if (cherryPick.exitCode() != 0) {
                List<String> conflictedFiles = conflictedFiles(repo, askPassScript);
                run(repo, askPassScript, gitExecutable(), "cherry-pick", "--abort");
                boolean conflict = cherryPick.output()
                        .toLowerCase(Locale.ROOT)
                        .contains("conflict");
                return new BackendDemoPrGitResult(
                        false,
                        true,
                        false,
                        conflict,
                        bugfixCommitSha,
                        "",
                        conflictedFiles,
                        "",
                        conflict
                                ? "BITBUCKET_MERGE_CONFLICT_REQUIRES_HUMAN"
                                : "BITBUCKET_BUGFIX_CHERRY_PICK_FAILED:" + cherryPick.output()
                );
            }
            String integrationCommitSha = head(repo, askPassScript);
            CommandResult pushIntegration =
                    run(repo, askPassScript, gitExecutable(), "push", "-u", "origin", integrationBranch);
            if (pushIntegration.exitCode() != 0) {
                return failure("BITBUCKET_INTEGRATION_BRANCH_PUSH_FAILED",
                        pushIntegration.output());
            }
            return new BackendDemoPrGitResult(
                    true,
                    true,
                    true,
                    false,
                    bugfixCommitSha,
                    integrationCommitSha,
                    "",
                    ""
            );
        } catch (Exception exception) {
            return failure(
                    "BITBUCKET_BACKEND_DEMO_PR_GIT_FAILED",
                    exception.getClass().getSimpleName()
            );
        } finally {
            deleteAskPass(askPassScript);
            cleanup(root);
        }
    }

    private String head(Path repo, Path askPassScript) {
        CommandResult sha = run(repo, askPassScript, gitExecutable(), "rev-parse", "HEAD");
        return sha.exitCode() == 0 ? sha.output().trim() : "";
    }

    private String gitExecutable() {
        String configured = properties.getIntegrations().getBitbucket().getGitExecutable();
        return isBlank(configured) ? "git" : configured;
    }

    private String cloneUrl(String projectKey, String repositorySlug) {
        String base = properties.getIntegrations().getBitbucket().getBaseUrl();
        if (isBlank(base)) {
            return "";
        }
        String normalizedBase = base.endsWith("/")
                ? base.substring(0, base.length() - 1)
                : base;
        String url = normalizedBase
                + "/scm/"
                + projectKey.toLowerCase(Locale.ROOT)
                + "/"
                + repositorySlug
                + ".git";
        return sanitize(url);
    }

    private CommandResult run(Path directory, Path askPassScript, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_EDITOR", "true");
            builder.environment().put("GIT_MERGE_AUTOEDIT", "no");
            if (askPassScript != null) {
                builder.environment().put("GIT_ASKPASS", askPassScript.toString());
            }
            builder.environment().put("REPLAYLAB_GIT_USERNAME",
                    nullToBlank(properties.getIntegrations().getBitbucket().getUsername()));
            builder.environment().put("REPLAYLAB_GIT_TOKEN",
                    nullToBlank(properties.getIntegrations().getBitbucket().getToken()));
            Process process = builder.start();
            boolean finished = process.waitFor(
                    TIMEOUT.toSeconds(),
                    TimeUnit.SECONDS
            );
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "BITBUCKET_GIT_OPERATION_TIMEOUT");
            }
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            return new CommandResult(process.exitValue(), sanitize(output));
        } catch (Exception exception) {
            return new CommandResult(1, exception.getClass().getSimpleName());
        }
    }

    private BackendDemoPrGitResult failure(String code, String output) {
        return new BackendDemoPrGitResult(
                false,
                false,
                false,
                false,
                "",
                "",
                credentialDiagnostics(),
                code + (isBlank(output) ? "" : ":" + sanitize(output))
        );
    }

    private List<String> conflictedFiles(Path repo, Path askPassScript) {
        CommandResult result = run(
                repo,
                askPassScript,
                gitExecutable(),
                "diff",
                "--name-only",
                "--diff-filter=U"
        );
        if (result.exitCode() != 0 || isBlank(result.output())) {
            return List.of();
        }
        return result.output().lines()
                .map(String::trim)
                .filter(value -> !isBlank(value))
                .map(this::sanitize)
                .toList();
    }

    private Path createAskPassScript(Path directory) throws Exception {
        Files.createDirectories(directory);
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        Path script = Files.createTempFile(
                directory,
                "replaylab-git-askpass-",
                windows ? ".cmd" : ".sh"
        );
        if (windows) {
            Files.writeString(
                    script,
                    """
                            @echo off
                            echo %1 | findstr /I "Username" >nul
                            if %errorlevel%==0 (
                              echo %REPLAYLAB_GIT_USERNAME%
                            ) else (
                              echo %REPLAYLAB_GIT_TOKEN%
                            )
                            """,
                    StandardCharsets.UTF_8
            );
        } else {
            Files.writeString(
                    script,
                    """
                            #!/bin/sh
                            case "$1" in
                            *Username*|*username*)
                            printf '%s\\n' "$REPLAYLAB_GIT_USERNAME"
                            ;;
                            *)
                            printf '%s\\n' "$REPLAYLAB_GIT_TOKEN"
                            ;;
                            esac
                            """,
                    StandardCharsets.UTF_8
            );
        }
        script.toFile().setExecutable(true);
        return script;
    }

    private void deleteAskPass(Path askPassScript) {
        if (askPassScript == null) {
            return;
        }
        try {
            Files.deleteIfExists(askPassScript);
        } catch (Exception ignored) {
            // Best-effort temp cleanup only.
        }
    }

    private String credentialDiagnostics() {
        String baseUrl = properties.getIntegrations().getBitbucket().getBaseUrl();
        String username = properties.getIntegrations().getBitbucket().getUsername();
        String token = properties.getIntegrations().getBitbucket().getToken();
        return "BITBUCKET_GIT_CREDENTIAL_DIAGNOSTICS"
                + "|bitbucketBaseUrlConfigured=" + !isBlank(baseUrl)
                + "|bitbucketUsernameConfigured=" + !isBlank(username)
                + "|bitbucketTokenConfigured=" + !isBlank(token)
                + "|effectiveUsernameMasked=" + maskUsername(username)
                + "|gitCloneHost=" + cloneHost(baseUrl)
                + "|credentialSource=BITBUCKET_USERNAME/BITBUCKET_TOKEN";
    }

    private String cloneHost(String baseUrl) {
        if (isBlank(baseUrl)) {
            return "";
        }
        try {
            return URI.create(baseUrl).getHost();
        } catch (Exception exception) {
            return "";
        }
    }

    private String maskUsername(String username) {
        if (isBlank(username)) {
            return "";
        }
        int at = username.indexOf('@');
        if (at > 0) {
            return username.charAt(0) + "****" + username.substring(at);
        }
        return username.charAt(0) + "****";
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        String username = properties.getIntegrations().getBitbucket().getUsername();
        String token = properties.getIntegrations().getBitbucket().getToken();
        for (String secret : List.of(username, token)) {
            if (!isBlank(secret)) {
                sanitized = sanitized.replace(secret, "[redacted]");
            }
        }
        return sanitized
                .replaceAll("https://[^\\s/@:]+:[^\\s/@]+@", "https://[redacted]@")
                .replaceAll("https://[^\\s']+@", "https://[redacted]@")
                .replaceAll("(?i)authorization", "[redacted]")
                .replaceAll("(?i)cookie", "[redacted]")
                .replaceAll("(?i)password", "[redacted]")
                .replaceAll("(?i)token", "[redacted]")
                .replaceAll("(?i)secret", "[redacted]")
                .replaceAll("(?i)apikey", "[redacted]")
                .replaceAll("(?i)privatekey", "[redacted]");
    }

    private void cleanup(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best-effort temp cleanup only.
                        }
                    });
        } catch (Exception ignored) {
            // Best-effort temp cleanup only.
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private record CommandResult(int exitCode, String output) {
    }
}
