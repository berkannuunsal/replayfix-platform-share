package com.etiya.replaylab.service;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class VersionedTestPathResolver {

    public VersionedPath resolve(
            Path workspace,
            String proposedRelativePath
    ) {
        Path normalizedWorkspace =
                workspace.toAbsolutePath()
                        .normalize();

        String normalizedRelative =
                normalizeRelativePath(
                        proposedRelativePath
                );

        Path original =
                normalizedWorkspace
                        .resolve(normalizedRelative)
                        .normalize();

        validateInsideWorkspace(
                normalizedWorkspace,
                original,
                normalizedRelative
        );

        if (!Files.exists(original)) {
            return new VersionedPath(
                    normalizedRelative,
                    original,
                    1
            );
        }

        int extensionIndex =
                normalizedRelative.lastIndexOf(
                        ".java"
                );

        if (extensionIndex < 0
                || extensionIndex
                        != normalizedRelative.length() - 5) {

            throw new IllegalArgumentException(
                    "Expected Java test path: "
                            + normalizedRelative
            );
        }

        String base =
                normalizedRelative.substring(
                        0,
                        extensionIndex
                );

        for (int version = 2;
             version <= 100;
             version++) {

            String candidateRelative =
                    base
                            + "V"
                            + version
                            + ".java";

            Path candidate =
                    normalizedWorkspace
                            .resolve(candidateRelative)
                            .normalize();

            validateInsideWorkspace(
                    normalizedWorkspace,
                    candidate,
                    candidateRelative
            );

            if (!Files.exists(candidate)) {
                return new VersionedPath(
                        candidateRelative,
                        candidate,
                        version
                );
            }
        }

        throw new IllegalStateException(
                "Cannot allocate versioned generated test path."
        );
    }

    private String normalizeRelativePath(
            String value
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    "Proposed relative path is empty."
            );
        }

        String normalized =
                value.replace(
                        '\\',
                        '/'
                ).trim();

        if (!normalized.startsWith(
                "src/test/java/"
        )) {
            throw new IllegalArgumentException(
                    "Generated Java test must be under src/test/java: "
                            + normalized
            );
        }

        if (!normalized.endsWith(
                ".java"
        )) {
            throw new IllegalArgumentException(
                    "Generated file must end with .java."
            );
        }

        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.contains(":/")) {

            throw new IllegalArgumentException(
                    "Unsafe generated test path: "
                            + normalized
            );
        }

        return normalized;
    }

    private void validateInsideWorkspace(
            Path workspace,
            Path target,
            String relative
    ) {
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException(
                    "Generated path escapes workspace: "
                            + relative
            );
        }
    }

    public record VersionedPath(
            String relativePath,
            Path absolutePath,
            int version
    ) {
    }
}
