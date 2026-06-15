package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.IntegrationModels.GeneratedFile;
import com.etiya.replayfix.model.IntegrationModels.GitPublishResult;
import com.etiya.replayfix.model.RepositoryCandidate;
import com.etiya.replayfix.model.SourceCheckoutResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GitWorkspaceService {
    private final ReplayFixProperties properties;

    public GitWorkspaceService(ReplayFixProperties properties) {
        this.properties = properties;
    }

    public Path prepare(ReplayCaseEntity replayCase, Target target) {
        Path workspace = Path.of(
            properties.getWorkspaceDir(),
            replayCase.getId().toString(),
            "repository"
        ).toAbsolutePath().normalize();

        if (properties.getMode() == ReplayFixProperties.Mode.DRY_RUN) {
            try {
                Files.createDirectories(workspace);
                Files.writeString(
                        workspace.resolve("DRY_RUN.txt"),
                        "Repository clone skipped in dry-run mode.\n",
                        StandardCharsets.UTF_8
                );
                return workspace;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        if (Files.exists(workspace.resolve(".git"))) {
            return workspace;
        }

        run(null, List.of(
                "git",
                "clone",
                "--branch",
                target.getGit().getSourceBranch(),
                target.getCloneUrl(),
                workspace.toString()
        ));

        if (replayCase.getSourceCommit() != null
                && !replayCase.getSourceCommit().isBlank()) {
            run(workspace, List.of(
                    "git",
                    "checkout",
                    replayCase.getSourceCommit()
            ));
        }

        return workspace;
    }

    public void writeFiles(
        Path workspace,
        List<GeneratedFile> files
    ) {
        Path normalizedWorkspace =
            workspace.toAbsolutePath().normalize();

        for (GeneratedFile file : files) {
            Path destination = normalizedWorkspace
                .resolve(file.path())
                .normalize();

            if (!destination.startsWith(normalizedWorkspace)) {
                throw new IllegalArgumentException(
                    "Generated file escapes workspace: "
                        + file.path()
                );
            }

            try {
                Files.createDirectories(destination.getParent());

                Files.writeString(
                    destination,
                    file.content(),
                    StandardCharsets.UTF_8
                );
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Cannot write generated file "
                        + file.path(),
                    e
                );
            }
        }
    }

    public GitPublishResult commitAndPush(
            ReplayCaseEntity replayCase,
            Target target,
            Path workspace
    ) {
        String branch = target.getGit().getBranchPrefix()
                + replayCase.getJiraKey().toLowerCase();

        if (properties.getMode() == ReplayFixProperties.Mode.DRY_RUN
                || !properties.getPolicy().isAllowGitPush()) {
            return new GitPublishResult(
                    branch,
                    "dry-run-commit",
                    workspace
            );
        }

        run(workspace, List.of("git", "checkout", "-b", branch));
        run(workspace, List.of("git", "add", "."));
        run(workspace, List.of(
                "git",
                "commit",
                "-m",
                "ReplayFix: verified remediation for "
                        + replayCase.getJiraKey()
        ));

        String sha = run(
                workspace,
                List.of("git", "rev-parse", "HEAD")
        ).trim();

        run(workspace, List.of(
                "git",
                "push",
                "-u",
                "origin",
                branch
        ));

        return new GitPublishResult(branch, sha, workspace);
    }

    public SourceCheckoutResult prepareReadOnly(
            ReplayCaseEntity replayCase,
            RepositoryCandidate candidate
    ) {
        if (!properties.getPolicy()
                .isAllowSourceCheckout()) {
            throw new IllegalStateException(
                    "Source checkout is disabled by policy."
            );
        }

        var bitbucket = properties.getIntegrations()
                .getBitbucket();

        if (!bitbucket.isEnabled()) {
            throw new IllegalStateException(
                    "Bitbucket integration is disabled."
            );
        }

        if (!"BASIC".equalsIgnoreCase(
                bitbucket.getAuthType()
        )) {
            throw new IllegalStateException(
                    "Read-only Git checkout currently expects "
                            + "Bitbucket BASIC authentication "
                            + "with username and HTTP access token."
            );
        }

        Path workspace = Path.of(
                properties.getWorkspaceDir(),
                replayCase.getId().toString(),
                "repositories",
                candidate.slug()
        ).toAbsolutePath().normalize();

        String branch = firstNonBlank(
                candidate.defaultBranch(),
                replayCase.getSourceBranch(),
                "master"
        );

        try {
            Files.createDirectories(
                    workspace.getParent()
            );

            if (Files.exists(
                    workspace.resolve(".git")
            )) {
                String sha = run(
                        workspace,
                        List.of(
                                bitbucket.getGitExecutable(),
                                "rev-parse",
                                "HEAD"
                        ),
                        Map.of()
                ).trim();

                return new SourceCheckoutResult(
                        replayCase.getId(),
                        candidate.projectKey(),
                        candidate.slug(),
                        branch,
                        sha,
                        workspace.toString(),
                        true
                );
            }

            String cloneUrl = resolveCloneUrl(
                    candidate,
                    bitbucket.getBaseUrl()
            );

            Path askPassScript = createAskPassScript(
                    workspace.getParent()
            );

            Map<String, String> environment =
                    new HashMap<>();

            environment.put(
                    "GIT_ASKPASS",
                    askPassScript.toString()
            );
            environment.put(
                    "GIT_TERMINAL_PROMPT",
                    "0"
            );
            environment.put(
                    "REPLAYFIX_GIT_USERNAME",
                    bitbucket.getUsername()
            );
            environment.put(
                    "REPLAYFIX_GIT_TOKEN",
                    bitbucket.getToken()
            );

            try {
                List<String> cloneCommand =
                        new ArrayList<>();

                cloneCommand.add(
                        bitbucket.getGitExecutable()
                );
                cloneCommand.add("clone");
                cloneCommand.add("--no-tags");
                cloneCommand.add("--single-branch");
                cloneCommand.add("--depth");
                cloneCommand.add(
                        String.valueOf(
                                properties.getPolicy()
                                        .getSourceHistoryDepth()
                        )
                );
                cloneCommand.add("--branch");
                cloneCommand.add(branch);
                cloneCommand.add(cloneUrl);
                cloneCommand.add(
                        workspace.toString()
                );

                run(
                        null,
                        cloneCommand,
                        environment
                );

                if (replayCase.getSourceCommit() != null
                        && !replayCase.getSourceCommit()
                        .isBlank()) {

                    run(
                            workspace,
                            List.of(
                                    bitbucket.getGitExecutable(),
                                    "fetch",
                                    "--depth",
                                    "1",
                                    "origin",
                                    replayCase.getSourceCommit()
                            ),
                            environment
                    );

                    run(
                            workspace,
                            List.of(
                                    bitbucket.getGitExecutable(),
                                    "checkout",
                                    "--detach",
                                    "FETCH_HEAD"
                            ),
                            environment
                    );
                }

            } finally {
                Files.deleteIfExists(
                        askPassScript
                );
            }

            String sha = run(
                    workspace,
                    List.of(
                            bitbucket.getGitExecutable(),
                            "rev-parse",
                            "HEAD"
                    ),
                    Map.of()
            ).trim();

            return new SourceCheckoutResult(
                    replayCase.getId(),
                    candidate.projectKey(),
                    candidate.slug(),
                    branch,
                    sha,
                    workspace.toString(),
                    false
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Read-only source checkout failed for "
                            + candidate.slug(),
                    exception
            );
        }
    }

    private String resolveCloneUrl(
            RepositoryCandidate candidate,
            String baseUrl
    ) {
        if (candidate.cloneUrl() != null
                && !candidate.cloneUrl().isBlank()) {
            return candidate.cloneUrl();
        }

        return baseUrl.replaceAll("/+$", "")
                + "/scm/"
                + candidate.projectKey()
                + "/"
                + candidate.slug()
                + ".git";
    }

    private Path createAskPassScript(
            Path directory
    ) throws Exception {
        Files.createDirectories(directory);

        Path script = Files.createTempFile(
                directory,
                "replayfix-git-askpass-",
                ".sh"
        );

        Files.writeString(
                script,
                """
                        #!/bin/sh
                        case "$1" in
                        *Username*|*username*)
                        printf '%s\\n' "$REPLAYFIX_GIT_USERNAME"
                        ;;
                        *)
                        printf '%s\\n' "$REPLAYFIX_GIT_TOKEN"
                        ;;
                        esac
                        """,
                StandardCharsets.UTF_8
        );

        if (!script.toFile().setExecutable(true)) {
            throw new IllegalStateException(
                    "Cannot make Git askpass script executable."
            );
        }

        return script;
    }

    private String firstNonBlank(
            String... values
    ) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String run(Path directory, List<String> command) {
        return run(
                directory,
                command,
                Map.of()
        );
    }

    private String run(
            Path directory,
            List<String> command,
            Map<String, String> environment
    ) {
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true);

            if (directory != null) {
                builder.directory(
                        directory.toFile()
                );
            }

            if (environment != null) {
                builder.environment()
                        .putAll(environment);
            }

            Process process = builder.start();
            String output = new String(
                    process.getInputStream()
                            .readAllBytes(),
                    StandardCharsets.UTF_8
            );
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Command failed with exit code "
                                + exitCode
                                + ": "
                                + sanitizeCommand(command)
                                + "\n"
                                + output
                );
            }
            return output;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Command execution failed: "
                            + sanitizeCommand(command),
                    exception
            );
        }
    }

    private String sanitizeCommand(
            List<String> command
    ) {
        return String.join(
                " ",
                command
        ).replaceAll(
                "(?i)(token|password)=[^\\s]+",
                "$1=***"
        );
    }
}
