package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.IncidentVersionResolution;
import com.etiya.replayfix.model.SourceCheckoutResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IncidentVersionResolverService {

    private static final Pattern COMMIT_SHA_PATTERN =
            Pattern.compile(
                    "(?i)(?<![0-9a-f])([0-9a-f]{7,40})(?![0-9a-f])"
            );

    public IncidentVersionResolution resolveAndCheckout(
            ReplayCaseEntity replayCase,
            SourceCheckoutResult checkout
    ) {
        Path repository = Path.of(checkout.workspace())
                .toAbsolutePath()
                .normalize();

        validateRepository(repository);

        List<String> warnings = new ArrayList<>();

        Resolution resolution = resolveVersion(
                repository,
                replayCase,
                checkout,
                warnings
        );

        checkoutDetached(
                repository,
                resolution.commitSha()
        );

        CommitMetadata metadata = readCommitMetadata(
                repository,
                resolution.commitSha()
        );

        return new IncidentVersionResolution(
                replayCase.getId(),
                checkout.repositorySlug(),
                checkout.branch(),
                resolution.strategy(),
                emptyIfNull(
                        replayCase.getSourceCommit()
                ),
                emptyIfNull(
                        replayCase.getImageTag()
                ),
                replayCase.getIncidentTime(),
                resolution.commitSha(),
                resolution.tag(),
                metadata.commitTime(),
                metadata.message(),
                resolution.exactMatch(),
                warnings
        );
    }

    private Resolution resolveVersion(
            Path repository,
            ReplayCaseEntity replayCase,
            SourceCheckoutResult checkout,
            List<String> warnings
    ) {
        String requestedCommit =
                replayCase.getSourceCommit();

        if (hasText(requestedCommit)) {
            Optional<String> commit = resolveCommit(
                    repository,
                    requestedCommit
            );

            if (commit.isPresent()) {
                return new Resolution(
                        "EXPLICIT_SOURCE_COMMIT",
                        commit.get(),
                        "",
                        true
                );
            }

            warnings.add(
                    "Requested source commit was not found: "
                            + requestedCommit
            );
        }

        String imageTag = replayCase.getImageTag();

        Optional<String> imageCommit =
                resolveCommitFromImageTag(
                        repository,
                        imageTag
                );

        if (imageCommit.isPresent()) {
            return new Resolution(
                    "IMAGE_TAG_COMMIT_SHA",
                    imageCommit.get(),
                    "",
                    true
            );
        }

        Optional<TagResolution> tagResolution =
                resolveGitTag(
                        repository,
                        imageTag
                );

        if (tagResolution.isPresent()) {
            return new Resolution(
                    "IMAGE_TAG_GIT_TAG",
                    tagResolution.get().commitSha(),
                    tagResolution.get().tag(),
                    true
            );
        }

        if (hasText(imageTag)) {
            warnings.add(
                    "Image tag could not be matched to a commit or Git tag: "
                            + imageTag
            );
        }

        Instant incidentTime =
                replayCase.getIncidentTime();

        if (incidentTime != null) {
            Optional<String> incidentCommit =
                    resolveCommitBeforeTime(
                            repository,
                            checkout.branch(),
                            incidentTime
                    );

            if (incidentCommit.isPresent()) {
                warnings.add(
                        "Commit was selected using incident time. "
                                + "Deployment metadata has not yet confirmed "
                                + "that this exact commit produced the image."
                );

                return new Resolution(
                        "INCIDENT_TIME",
                        incidentCommit.get(),
                        "",
                        false
                );
            }

            warnings.add(
                    "No commit was found before incident time: "
                            + incidentTime
            );
        }

        String headCommit = resolveCommit(
                repository,
                "HEAD"
        ).orElseThrow(() ->
                new IllegalStateException(
                        "Cannot resolve checkout HEAD commit."
                )
        );

        warnings.add(
                "Falling back to checkout HEAD. "
                        + "This may not be the incident version."
        );

        return new Resolution(
                "CHECKOUT_HEAD",
                headCommit,
                "",
                false
        );
    }

    private Optional<String> resolveCommitFromImageTag(
            Path repository,
            String imageTag
    ) {
        if (!hasText(imageTag)) {
            return Optional.empty();
        }

        Matcher matcher =
                COMMIT_SHA_PATTERN.matcher(imageTag);

        while (matcher.find()) {
            Optional<String> commit = resolveCommit(
                    repository,
                    matcher.group(1)
            );

            if (commit.isPresent()) {
                return commit;
            }
        }

        return Optional.empty();
    }

    private Optional<TagResolution> resolveGitTag(
            Path repository,
            String imageTag
    ) {
        String version = extractImageVersion(imageTag);

        if (!hasText(version)) {
            return Optional.empty();
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(version);

        if (!version.startsWith("v")) {
            candidates.add("v" + version);
        }

        for (String tag : candidates) {
            Optional<String> commit = resolveCommit(
                    repository,
                    "refs/tags/" + tag
            );

            if (commit.isPresent()) {
                return Optional.of(
                        new TagResolution(
                                tag,
                                commit.get()
                        )
                );
            }
        }

        return Optional.empty();
    }

    private Optional<String> resolveCommitBeforeTime(
            Path repository,
            String branch,
            Instant incidentTime
    ) {
        List<String> refs = List.of(
                "origin/" + branch,
                branch,
                "HEAD"
        );

        for (String ref : refs) {
            CommandResult result = runAllowFailure(
                    repository,
                    List.of(
                            "git",
                            "rev-list",
                            "-1",
                            "--before=" + incidentTime,
                            ref
                    )
            );

            String value = result.output().trim();

            if (result.exitCode() == 0
                    && !value.isBlank()) {
                return Optional.of(value);
            }
        }

        return Optional.empty();
    }

    private Optional<String> resolveCommit(
            Path repository,
            String reference
    ) {
        if (!hasText(reference)) {
            return Optional.empty();
        }

        CommandResult result = runAllowFailure(
                repository,
                List.of(
                        "git",
                        "rev-parse",
                        "--verify",
                        reference + "^{commit}"
                )
        );

        if (result.exitCode() != 0) {
            return Optional.empty();
        }

        String value = result.output().trim();

        return value.isBlank()
                ? Optional.empty()
                : Optional.of(value);
    }

    private CommitMetadata readCommitMetadata(
            Path repository,
            String commitSha
    ) {
        String commitTimeValue = run(
                repository,
                List.of(
                        "git",
                        "show",
                        "-s",
                        "--format=%cI",
                        commitSha
                )
        ).trim();

        String message = run(
                repository,
                List.of(
                        "git",
                        "show",
                        "-s",
                        "--format=%s",
                        commitSha
                )
        ).trim();

        Instant commitTime;

        try {
            commitTime = Instant.parse(commitTimeValue);
        } catch (Exception exception) {
            commitTime = null;
        }

        return new CommitMetadata(
                commitTime,
                message
        );
    }

    private void checkoutDetached(
            Path repository,
            String commitSha
    ) {
        run(
                repository,
                List.of(
                        "git",
                        "checkout",
                        "--detach",
                        "--force",
                        commitSha
                )
        );
    }

    private String extractImageVersion(
            String imageTag
    ) {
        if (!hasText(imageTag)) {
            return "";
        }

        String value = imageTag.trim();

        int digestIndex = value.indexOf('@');
        if (digestIndex >= 0) {
            value = value.substring(
                    0,
                    digestIndex
            );
        }

        int lastSlash = value.lastIndexOf('/');
        int lastColon = value.lastIndexOf(':');

        if (lastColon > lastSlash) {
            return value.substring(
                    lastColon + 1
            );
        }

        return value;
    }

    private void validateRepository(Path repository) {
        if (!Files.isDirectory(repository)) {
            throw new IllegalArgumentException(
                    "Repository workspace does not exist: "
                            + repository
            );
        }

        if (!Files.isDirectory(
                repository.resolve(".git")
        )) {
            throw new IllegalArgumentException(
                    "Workspace is not a Git repository: "
                            + repository
            );
        }
    }

    private String run(
            Path directory,
            List<String> command
    ) {
        CommandResult result = runAllowFailure(
                directory,
                command
        );

        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Git command failed: "
                            + String.join(" ", command)
                            + "\n"
                            + result.output()
            );
        }

        return result.output();
    }

    private CommandResult runAllowFailure(
            Path directory,
            List<String> command
    ) {
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .directory(
                                    directory.toFile()
                            )
                            .redirectErrorStream(true);

            Process process = builder.start();

            String output = new String(
                    process.getInputStream()
                            .readAllBytes(),
                    StandardCharsets.UTF_8
            );

            int exitCode = process.waitFor();

            return new CommandResult(
                    exitCode,
                    output
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot execute Git command: "
                            + String.join(" ", command),
                    exception
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private record Resolution(
            String strategy,
            String commitSha,
            String tag,
            boolean exactMatch
    ) {
    }

    private record TagResolution(
            String tag,
            String commitSha
    ) {
    }

    private record CommitMetadata(
            Instant commitTime,
            String message
    ) {
    }

    private record CommandResult(
            int exitCode,
            String output
    ) {
    }
}
