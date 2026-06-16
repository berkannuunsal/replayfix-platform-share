package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.SafeProcessResult;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class SafeMavenTestRunner {

    private static final Set<String> SENSITIVE_ENVIRONMENT_KEYS =
            Set.of(
                    "JENKINS_TOKEN",
                    "JENKINS_API_TOKEN",
                    "BITBUCKET_TOKEN",
                    "JIRA_TOKEN",
                    "LOKI_TOKEN",
                    "TEMPO_TOKEN",
                    "AI_TOKEN",
                    "ARGOCD_TOKEN",
                    "GRAFANA_TOKEN",
                    "REPLAYFIX_GIT_TOKEN"
            );

    private final ReplayFixProperties properties;

    public SafeMavenTestRunner(
            ReplayFixProperties properties
    ) {
        this.properties = properties;
    }

    public SafeProcessResult runSingleTest(
            Path workspace,
            String testClass,
            String testMethod
    ) {
        Path normalizedWorkspace =
                workspace.toAbsolutePath()
                        .normalize();

        if (!Files.isDirectory(
                normalizedWorkspace
        )) {
            throw new IllegalArgumentException(
                    "Workspace does not exist: "
                            + normalizedWorkspace
            );
        }

        String safeClass =
                requireJavaIdentifier(
                        testClass,
                        "testClass"
                );

        String safeMethod =
                requireJavaIdentifier(
                        testMethod,
                        "testMethod"
                );

        String selector =
                safeClass
                        + "#"
                        + safeMethod;

        String executable =
                resolveMavenExecutable(
                        normalizedWorkspace
                );

        List<String> command =
                new ArrayList<>();

        command.add(executable);
        command.add("-B");
        command.add("-Dtest=" + selector);
        command.add("-DfailIfNoTests=false");
        command.add("test");

        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(
                                normalizedWorkspace.toFile()
                        )
                        .redirectErrorStream(true);

        Map<String, String> environment =
                builder.environment();

        SENSITIVE_ENVIRONMENT_KEYS.forEach(
                environment::remove
        );

        List<String> dynamicSecretKeys =
                environment.keySet()
                        .stream()
                        .filter(key -> {
                            String upper =
                                    key.toUpperCase();

                            return upper.endsWith("_TOKEN")
                                    || upper.endsWith("_PASSWORD")
                                    || upper.contains("SECRET");
                        })
                        .toList();

        dynamicSecretKeys.forEach(
                environment::remove
        );

        Instant startedAt =
                Instant.now();

        Process process = null;

        try {
            process = builder.start();

            ByteArrayOutputStream outputBuffer =
                    new ByteArrayOutputStream();

            Process finalProcess = process;

            Thread outputReader =
                    new Thread(() -> {
                        try {
                            finalProcess
                                    .getInputStream()
                                    .transferTo(
                                            outputBuffer
                                    );
                        } catch (Exception ignored) {
                        }
                    });

            outputReader.setDaemon(true);
            outputReader.start();

            long timeoutSeconds =
                    properties.getPolicy()
                            .getLocalTestTimeoutSeconds();

            boolean completed =
                    process.waitFor(
                            timeoutSeconds,
                            TimeUnit.SECONDS
                    );

            if (!completed) {
                process.destroy();

                if (!process.waitFor(
                        5,
                        TimeUnit.SECONDS
                )) {
                    process.destroyForcibly();
                }
            }

            outputReader.join(
                    Duration.ofSeconds(10)
                            .toMillis()
            );

            Instant finishedAt =
                    Instant.now();

            String output =
                    outputBuffer.toString(
                            StandardCharsets.UTF_8
                    );

            output =
                    truncate(
                            output,
                            properties.getPolicy()
                                    .getLocalTestMaxOutputChars()
                    );

            return new SafeProcessResult(
                    startedAt,
                    finishedAt,
                    Duration.between(
                            startedAt,
                            finishedAt
                    ).toMillis(),
                    completed
                            ? process.exitValue()
                            : null,
                    !completed,
                    output
            );

        } catch (Exception exception) {
            if (process != null
                    && process.isAlive()) {
                process.destroyForcibly();
            }

            throw new IllegalStateException(
                    "Cannot execute safe Maven test.",
                    exception
            );
        }
    }

    private String resolveMavenExecutable(
            Path workspace
    ) {
        boolean windows =
                System.getProperty("os.name")
                        .toLowerCase()
                        .contains("win");

        Path wrapper =
                workspace.resolve(
                        windows
                                ? "mvnw.cmd"
                                : "mvnw"
                );

        if (Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath()
                    .toString();
        }

        String configured =
                properties.getPolicy()
                        .getMavenExecutable();

        if (configured == null
                || configured.isBlank()) {
            throw new IllegalStateException(
                    "Maven executable is not configured."
            );
        }

        if (configured.contains(" ")
                || configured.contains(";")
                || configured.contains("&")
                || configured.contains("|")
                || configured.contains(">")
                || configured.contains("<")) {

            throw new IllegalArgumentException(
                    "Unsafe Maven executable value."
            );
        }

        return configured;
    }

    private String requireJavaIdentifier(
            String value,
            String field
    ) {
        if (value == null
                || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is empty."
            );
        }

        for (int index = 0;
             index < value.length();
             index++) {

            char character =
                    value.charAt(index);

            boolean valid =
                    index == 0
                            ? Character.isJavaIdentifierStart(
                                    character
                            )
                            : Character.isJavaIdentifierPart(
                                    character
                            );

            if (!valid) {
                throw new IllegalArgumentException(
                        "Unsafe Java identifier for "
                                + field
                                + ": "
                                + value
                );
            }
        }

        return value;
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }

        if (maxLength <= 0
                || value.length()
                        <= maxLength) {
            return value;
        }

        return value.substring(
                0,
                maxLength
        );
    }
}
