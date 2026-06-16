package com.etiya.replayfix.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class AtomicWorkspaceFileWriter {

    public Path writeNewFile(
            Path workspace,
            String relativePath,
            String content
    ) {
        Path normalizedWorkspace =
                workspace.toAbsolutePath()
                        .normalize();

        Path target =
                normalizedWorkspace
                        .resolve(relativePath)
                        .normalize();

        if (!target.startsWith(
                normalizedWorkspace
        )) {
            throw new IllegalArgumentException(
                    "Generated file escapes workspace: "
                            + relativePath
            );
        }

        if (Files.exists(target)) {
            throw new IllegalStateException(
                    "Generated test file already exists: "
                            + target
            );
        }

        try {
            Files.createDirectories(
                    target.getParent()
            );

            Path temporary =
                    Files.createTempFile(
                            target.getParent(),
                            ".replayfix-",
                            ".tmp"
                    );

            try {
                Files.writeString(
                        temporary,
                        content,
                        StandardCharsets.UTF_8
                );

                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE
                    );

                } catch (Exception atomicMoveFailure) {
                    Files.move(
                            temporary,
                            target
                    );
                }

            } finally {
                Files.deleteIfExists(
                        temporary
                );
            }

            return target;

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot write generated test file: "
                            + target,
                    exception
            );
        }
    }
}
