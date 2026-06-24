package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessTest2DemoPrGitOperations implements Test2DemoPrGitOperations {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final ReplayFixProperties properties;

    public ProcessTest2DemoPrGitOperations(ReplayFixProperties properties) {
        this.properties = properties;
    }

    @Override
    public Test2DemoPrGitResult commitAndPushIntegrationBranch(
            String projectKey,
            String repositorySlug,
            String targetBranch,
            String integrationBranch,
            String generatedFilePath,
            String generatedFileContent,
            String commitMessage
    ) {
        Path root = null;
        try {
            String cloneUrl = cloneUrl(projectKey, repositorySlug);
            if (isBlank(cloneUrl)) {
                return failure("BITBUCKET_CLONE_URL_NOT_CONFIGURED", "");
            }
            root = Files.createTempDirectory("replayfix-test2-demo-pr-");
            Path repo = root.resolve(repositorySlug);
            CommandResult clone = run(
                    root,
                    "git",
                    "clone",
                    "--branch",
                    targetBranch,
                    "--single-branch",
                    cloneUrl,
                    repositorySlug
            );
            if (clone.exitCode() != 0) {
                return failure("BITBUCKET_TARGET_BRANCH_NOT_FOUND", clone.output());
            }
            CommandResult checkout = run(
                    repo,
                    "git",
                    "checkout",
                    "-B",
                    integrationBranch
            );
            if (checkout.exitCode() != 0) {
                return failure("BITBUCKET_INTEGRATION_BRANCH_CHECKOUT_FAILED", checkout.output());
            }
            Path generated = repo.resolve(generatedFilePath).normalize();
            if (!generated.startsWith(repo)) {
                return failure("PATH_OUTSIDE_TEMP_WORKSPACE_REJECTED", "");
            }
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, generatedFileContent, StandardCharsets.UTF_8);
            CommandResult add = run(repo, "git", "add", generatedFilePath);
            if (add.exitCode() != 0) {
                return failure("BITBUCKET_DEMO_FILE_ADD_FAILED", add.output());
            }
            CommandResult commit = run(repo, "git", "commit", "-m", commitMessage);
            if (commit.exitCode() != 0
                    && !commit.output().toLowerCase(Locale.ROOT).contains("nothing to commit")) {
                return failure("BITBUCKET_DEMO_FILE_COMMIT_FAILED", commit.output());
            }
            CommandResult sha = run(repo, "git", "rev-parse", "HEAD");
            String commitSha = sha.exitCode() == 0 ? sha.output().trim() : "";
            CommandResult push = run(repo, "git", "push", "-u", "origin", integrationBranch);
            if (push.exitCode() != 0) {
                return failure("BITBUCKET_INTEGRATION_BRANCH_PUSH_FAILED", push.output());
            }
            return new Test2DemoPrGitResult(true, commitSha, "", "");
        } catch (Exception exception) {
            return failure("BITBUCKET_TEST2_DEMO_PR_GIT_FAILED", exception.getClass().getSimpleName());
        } finally {
            cleanup(root);
        }
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
        String username = properties.getIntegrations().getBitbucket().getUsername();
        String token = properties.getIntegrations().getBitbucket().getToken();
        if (isBlank(username) || isBlank(token)) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String userInfo = encode(username) + ":" + encode(token);
            return new URI(
                    uri.getScheme(),
                    userInfo,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            ).toString();
        } catch (Exception exception) {
            return url;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private CommandResult run(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
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
            return new CommandResult(process.exitValue(), sanitize(output));
        } catch (Exception exception) {
            return new CommandResult(
                    1,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private Test2DemoPrGitResult failure(String code, String output) {
        return new Test2DemoPrGitResult(
                false,
                "",
                "",
                code + (isBlank(output) ? "" : ":" + sanitize(output))
        );
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

    private record CommandResult(int exitCode, String output) {
    }
}
