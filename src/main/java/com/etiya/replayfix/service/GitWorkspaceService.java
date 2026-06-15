package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.IntegrationModels.GeneratedFile;
import com.etiya.replayfix.model.IntegrationModels.GitPublishResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private String run(Path directory, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
            }

            Process process = builder.start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Command failed: "
                                + String.join(" ", command)
                                + "\n"
                                + output
                );
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Command execution failed: "
                            + String.join(" ", command),
                    e
            );
        }
    }
}
