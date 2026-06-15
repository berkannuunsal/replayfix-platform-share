package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.IntegrationModels.ProvisionResult;
import com.etiya.replayfix.model.IntegrationModels.ReplayResult;
import com.etiya.replayfix.service.KubernetesManifestFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class KubectlKubernetesClient implements KubernetesClient {
    private final ReplayFixProperties properties;
    private final KubernetesManifestFactory manifestFactory;

    public KubectlKubernetesClient(
            ReplayFixProperties properties,
            KubernetesManifestFactory manifestFactory
    ) {
        this.properties = properties;
        this.manifestFactory = manifestFactory;
    }

    @Override
    public ProvisionResult provision(ReplayCaseEntity replayCase, Target target) {
        Path caseDirectory = Path.of(
                properties.getWorkspaceDir(),
                replayCase.getId().toString()
        );
        Path manifestPath = caseDirectory.resolve("kubernetes-environment.yml");

        try {
            Files.createDirectories(caseDirectory);
            Files.writeString(
                    manifestPath,
                    manifestFactory.createEnvironmentManifest(replayCase, target),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write Kubernetes manifest", e);
        }

        var cfg = properties.getIntegrations().getKubernetes();
        if (properties.getMode() == ReplayFixProperties.Mode.DRY_RUN || !cfg.isEnabled()) {
            return new ProvisionResult(
                    replayCase.getNamespace(),
                    "app",
                    "replayfix-postgres",
                    manifestPath.toString()
            );
        }

        run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "apply",
                "-f",
                manifestPath.toString()
        ), true);

        run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "-n",
                replayCase.getNamespace(),
                "rollout",
                "status",
                "deployment/replayfix-postgres",
                "--timeout=300s"
        ), true);

        run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "-n",
                replayCase.getNamespace(),
                "rollout",
                "status",
                "deployment/app",
                "--timeout=300s"
        ), true);

        return new ProvisionResult(
                replayCase.getNamespace(),
                "app",
                "replayfix-postgres",
                manifestPath.toString()
        );
    }

    @Override
    public ReplayResult replay(ReplayCaseEntity replayCase, Target target) {
        Path caseDirectory = Path.of(
                properties.getWorkspaceDir(),
                replayCase.getId().toString()
        );
        Path jobManifest = caseDirectory.resolve("replay-job.yml");

        try {
            Files.writeString(
                    jobManifest,
                    manifestFactory.createReplayJobManifest(replayCase, target),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write replay job manifest", e);
        }

        var cfg = properties.getIntegrations().getKubernetes();
        if (properties.getMode() == ReplayFixProperties.Mode.DRY_RUN || !cfg.isEnabled()) {
            return new ReplayResult(
                    true,
                    target.getReplay().getExpectedStatus(),
                    "dry-run response",
                    "dry-run application logs",
                    target.getReplay().getExpectedLogRegex()
            );
        }

        run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "apply",
                "-f",
                jobManifest.toString()
        ), true);

        run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "-n",
                replayCase.getNamespace(),
                "wait",
                "--for=condition=complete",
                "job/replay-runner",
                "--timeout=180s"
        ), true);

        String replayOutput = run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "-n",
                replayCase.getNamespace(),
                "logs",
                "job/replay-runner"
        ), false);

        String applicationLogs = run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "-n",
                replayCase.getNamespace(),
                "logs",
                "deployment/app",
                "--tail=500"
        ), false);

        int status = parseStatus(replayOutput);
        boolean reproduced =
                status == target.getReplay().getExpectedStatus()
                        && applicationLogs.matches(
                                "(?s).*" + target.getReplay().getExpectedLogRegex() + ".*"
                        );

        return new ReplayResult(
                reproduced,
                status,
                replayOutput,
                applicationLogs,
                reproduced
                        ? target.getReplay().getExpectedLogRegex()
                        : "not-matched"
        );
    }

    @Override
    public void cleanup(String namespace) {
        var cfg = properties.getIntegrations().getKubernetes();
        if (properties.getMode() == ReplayFixProperties.Mode.DRY_RUN
                || !cfg.isEnabled()
                || namespace == null
                || namespace.isBlank()) {
            return;
        }

        run(List.of(
                cfg.getKubectlPath(),
                contextArgument(),
                "delete",
                "namespace",
                namespace,
                "--ignore-not-found=true"
        ), true);
    }

    private String contextArgument() {
        String context = properties.getIntegrations().getKubernetes().getContext();
        return context == null || context.isBlank()
                ? "--context="
                : "--context=" + context;
    }

    private String run(List<String> command, boolean failOnError) {
        List<String> normalized = new ArrayList<>();
        command.stream()
                .filter(value -> value != null && !value.equals("--context="))
                .forEach(normalized::add);

        try {
            Process process = new ProcessBuilder(normalized)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            int exitCode = process.waitFor();

            if (failOnError && exitCode != 0) {
                throw new IllegalStateException(
                        "Command failed: " + String.join(" ", normalized) + "\n" + output
                );
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Command execution failed: " + String.join(" ", normalized),
                    e
            );
        }
    }

    private int parseStatus(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("REPLAYFIX_HTTP_STATUS=")) {
                return Integer.parseInt(
                        line.substring(line.indexOf('=') + 1).trim()
                );
            }
        }
        return 0;
    }
}
