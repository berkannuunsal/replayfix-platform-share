package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class SafeKubectlRunner {

    private static final int MAX_OUTPUT_CHARS = 10_000_000;
    private static final Pattern CONTEXT_PATTERN =
            Pattern.compile("[A-Za-z0-9:_./@-]+");
    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final Pattern RESOURCE_NAME_PATTERN =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    private static final List<String> SENSITIVE_ENV_PATTERNS = Arrays.asList(
            "_TOKEN",
            "_PASSWORD",
            "SECRET",
            "JIRA_TOKEN",
            "LOKI_TOKEN",
            "BITBUCKET_TOKEN",
            "JENKINS_TOKEN",
            "AI_TOKEN",
            "REPLAYFIX_GIT_TOKEN"
    );

    private final ReplayFixProperties properties;
    private final ObjectMapper objectMapper;

    public SafeKubectlRunner(
            ReplayFixProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public JsonNode getDeployment(
            String context,
            String namespace,
            String deploymentName
    ) {
        validateContext(context);
        validateNamespace(namespace);
        validateResourceName(deploymentName);

        List<String> command = new ArrayList<>();
        command.add(getKubectlExecutable());

        if (context != null && !context.isBlank()) {
            command.add("--context");
            command.add(context);
        }

        command.add("-n");
        command.add(namespace);
        command.add("get");
        command.add("deployment");
        command.add(deploymentName);
        command.add("-o");
        command.add("json");

        return executeForJson(command);
    }

    public JsonNode listReplicaSets(
            String context,
            String namespace,
            String selector
    ) {
        validateContext(context);
        validateNamespace(namespace);

        List<String> command = new ArrayList<>();
        command.add(getKubectlExecutable());

        if (context != null && !context.isBlank()) {
            command.add("--context");
            command.add(context);
        }

        command.add("-n");
        command.add(namespace);
        command.add("get");
        command.add("replicasets");

        if (selector != null && !selector.isBlank()) {
            command.add("-l");
            command.add(selector);
        }

        command.add("-o");
        command.add("json");

        return executeForJson(command);
    }

    public JsonNode listPods(
            String context,
            String namespace,
            String selector
    ) {
        validateContext(context);
        validateNamespace(namespace);

        List<String> command = new ArrayList<>();
        command.add(getKubectlExecutable());

        if (context != null && !context.isBlank()) {
            command.add("--context");
            command.add(context);
        }

        command.add("-n");
        command.add(namespace);
        command.add("get");
        command.add("pods");

        if (selector != null && !selector.isBlank()) {
            command.add("-l");
            command.add(selector);
        }

        command.add("-o");
        command.add("json");

        return executeForJson(command);
    }

    public String currentContext() {
        List<String> command = Arrays.asList(
                getKubectlExecutable(),
                "config",
                "current-context"
        );

        return executeForString(command).trim();
    }

    public boolean canI(
            String verb,
            String resource,
            boolean allNamespaces
    ) {
        List<String> command = new ArrayList<>();
        command.add(getKubectlExecutable());
        command.add("auth");
        command.add("can-i");
        command.add(verb);
        command.add(resource);

        if (allNamespaces) {
            command.add("--all-namespaces");
        }

        String result = executeForString(command).trim().toLowerCase();
        return "yes".equals(result);
    }

    private JsonNode executeForJson(List<String> command) {
        String output = executeForString(command);

        try {
            return objectMapper.readTree(output);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "kubectl output is not valid JSON.",
                    exception
            );
        }
    }

    private String executeForString(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(getSanitizedEnvironment());

            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                process.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() + line.length() > MAX_OUTPUT_CHARS) {
                            break;
                        }
                        output.append(line).append("\n");
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });

            Thread errorReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                process.getErrorStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (error.length() + line.length() > MAX_OUTPUT_CHARS) {
                            break;
                        }
                        error.append(line).append("\n");
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });

            outputReader.start();
            errorReader.start();

            int timeoutSeconds = properties.getPolicy().getKubernetesTimeoutSeconds();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "kubectl command timed out after " + timeoutSeconds + " seconds."
                );
            }

            outputReader.join(1000);
            errorReader.join(1000);

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        "kubectl command failed with exit code "
                                + exitCode
                                + ": "
                                + error
                );
            }

            return output.toString();

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "kubectl command interrupted.",
                    exception
            );

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot execute kubectl command.",
                    exception
            );
        }
    }

    private Map<String, String> getSanitizedEnvironment() {
        Map<String, String> environment = new java.util.HashMap<>(
                System.getenv()
        );

        environment.keySet().removeIf(key -> {
            String upperKey = key.toUpperCase();
            return SENSITIVE_ENV_PATTERNS.stream()
                    .anyMatch(upperKey::contains);
        });

        return environment;
    }

    private String getKubectlExecutable() {
        String executable = properties.getPolicy().getKubectlExecutable();
        return executable != null && !executable.isBlank()
                ? executable
                : "kubectl";
    }

    private void validateContext(String context) {
        if (context == null || context.isBlank()) {
            return;
        }

        if (!CONTEXT_PATTERN.matcher(context).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Kubernetes context: " + context
            );
        }
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException(
                    "Namespace cannot be null or blank"
            );
        }

        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Kubernetes namespace: " + namespace
            );
        }
    }

    private void validateResourceName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Resource name cannot be null or blank"
            );
        }

        if (!RESOURCE_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Kubernetes resource name: " + name
            );
        }
    }
}
