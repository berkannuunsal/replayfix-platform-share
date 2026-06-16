package com.etiya.replayfix.service;

import com.etiya.replayfix.model.KubernetesConnectivityCheck;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KubernetesConnectivityService {

    private final SafeKubectlRunner kubectlRunner;

    public KubernetesConnectivityService(SafeKubectlRunner kubectlRunner) {
        this.kubectlRunner = kubectlRunner;
    }

    public KubernetesConnectivityCheck checkConnectivity() {
        List<String> warnings = new ArrayList<>();
        String currentContext = null;
        Map<String, Boolean> permissions = new LinkedHashMap<>();

        try {
            currentContext = kubectlRunner.currentContext();

        } catch (Exception exception) {
            warnings.add("Cannot get current kubectl context: " + exception.getMessage());
            return new KubernetesConnectivityCheck(
                    false,
                    null,
                    permissions,
                    false,
                    warnings
            );
        }

        try {
            permissions.put("getDeploymentsAllNamespaces",
                    kubectlRunner.canI("get", "deployments", true));
            permissions.put("listDeploymentsAllNamespaces",
                    kubectlRunner.canI("list", "deployments", true));
            permissions.put("getReplicaSetsAllNamespaces",
                    kubectlRunner.canI("get", "replicasets", true));
            permissions.put("listReplicaSetsAllNamespaces",
                    kubectlRunner.canI("list", "replicasets", true));
            permissions.put("getPodsAllNamespaces",
                    kubectlRunner.canI("get", "pods", true));
            permissions.put("listPodsAllNamespaces",
                    kubectlRunner.canI("list", "pods", true));

        } catch (Exception exception) {
            warnings.add("Cannot check permissions: " + exception.getMessage());
        }

        boolean allPermissionsGranted = permissions.values().stream()
                .allMatch(granted -> granted != null && granted);

        if (!allPermissionsGranted) {
            warnings.add("Some read-only permissions are missing.");
        }

        return new KubernetesConnectivityCheck(
                allPermissionsGranted,
                currentContext,
                permissions,
                false,
                warnings
        );
    }
}
