package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.*;
import com.etiya.replaylab.service.ContainerImageParser;
import com.etiya.replaylab.service.EvidenceSanitizer;
import com.etiya.replaylab.service.SafeKubectlRunner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class KubectlKubernetesRuntimeClient implements KubernetesRuntimeClient {

    private final SafeKubectlRunner kubectlRunner;
    private final ContainerImageParser imageParser;
    private final EvidenceSanitizer evidenceSanitizer;

    public KubectlKubernetesRuntimeClient(
            SafeKubectlRunner kubectlRunner,
            ContainerImageParser imageParser,
            EvidenceSanitizer evidenceSanitizer
    ) {
        this.kubectlRunner = kubectlRunner;
        this.imageParser = imageParser;
        this.evidenceSanitizer = evidenceSanitizer;
    }

    @Override
    public KubernetesRuntimeInventory collect(
            UUID caseId,
            String applicationKey
    ) {
        List<String> warnings = new ArrayList<>();
        String context = null;

        try {
            context = kubectlRunner.currentContext();
        } catch (Exception exception) {
            warnings.add("Cannot get current context: " + exception.getMessage());
        }

        List<KubernetesDeploymentRuntime> deployments = new ArrayList<>();
        String clusterEvidenceLevel = "NO_RUNTIME_EVIDENCE";

        List<DeploymentTarget> targets = getDeploymentTargets(applicationKey);

        for (DeploymentTarget target : targets) {
            try {
                KubernetesDeploymentRuntime deployment = collectDeployment(
                        context,
                        target.namespace,
                        target.deploymentName
                );

                deployments.add(deployment);

                if (!deployment.pods().isEmpty()) {
                    clusterEvidenceLevel = "CURRENT_RUNTIME";
                } else if (!deployment.replicaSets().isEmpty()
                        && "CURRENT_RUNTIME".equals(clusterEvidenceLevel)) {
                    clusterEvidenceLevel = "RETAINED_REPLICASET_HISTORY";
                }

            } catch (Exception exception) {
                warnings.add(
                        "Cannot collect deployment "
                                + target.deploymentName
                                + " in namespace "
                                + target.namespace
                                + ": "
                                + exception.getMessage()
                );
            }
        }

        return new KubernetesRuntimeInventory(
                caseId,
                applicationKey,
                context,
                clusterEvidenceLevel,
                deployments,
                warnings
        );
    }

    private KubernetesDeploymentRuntime collectDeployment(
            String context,
            String namespace,
            String deploymentName
    ) {
        JsonNode deploymentJson = kubectlRunner.getDeployment(
                context,
                namespace,
                deploymentName
        );

        String creationTimestamp = safeText(
                deploymentJson.at("/metadata/creationTimestamp")
        );

        String revision = safeText(
                deploymentJson.at("/metadata/annotations/deployment.kubernetes.io~1revision")
        );

        long generation = deploymentJson.at("/metadata/generation").asLong(0);

        int desiredReplicas = deploymentJson.at("/spec/replicas").asInt(0);
        int availableReplicas = deploymentJson.at("/status/availableReplicas").asInt(0);

        List<String> images = extractImages(deploymentJson.at("/spec/template/spec/containers"));

        Map<String, String> annotations = sanitizeMap(
                jsonToMap(deploymentJson.at("/metadata/annotations"))
        );

        Map<String, String> labels = sanitizeMap(
                jsonToMap(deploymentJson.at("/metadata/labels"))
        );

        String selector = buildSelector(labels);

        List<KubernetesReplicaSetRuntime> replicaSets = collectReplicaSets(
                context,
                namespace,
                selector,
                deploymentName
        );

        List<KubernetesPodRuntime> pods = collectPods(
                context,
                namespace,
                selector
        );

        return new KubernetesDeploymentRuntime(
                namespace,
                deploymentName,
                creationTimestamp,
                revision,
                generation,
                desiredReplicas,
                availableReplicas,
                images,
                annotations,
                labels,
                replicaSets,
                pods
        );
    }

    private List<KubernetesReplicaSetRuntime> collectReplicaSets(
            String context,
            String namespace,
            String selector,
            String deploymentName
    ) {
        try {
            JsonNode replicaSetsJson = kubectlRunner.listReplicaSets(
                    context,
                    namespace,
                    selector
            );

            JsonNode items = replicaSetsJson.get("items");
            if (items == null || !items.isArray()) {
                return List.of();
            }

            return StreamSupport.stream(items.spliterator(), false)
                    .map(rsJson -> mapReplicaSet(rsJson, deploymentName))
                    .collect(Collectors.toList());

        } catch (Exception exception) {
            return List.of();
        }
    }

    private KubernetesReplicaSetRuntime mapReplicaSet(
            JsonNode rsJson,
            String deploymentName
    ) {
        String namespace = safeText(rsJson.at("/metadata/namespace"));
        String name = safeText(rsJson.at("/metadata/name"));

        String revision = safeText(
                rsJson.at("/metadata/annotations/deployment.kubernetes.io~1revision")
        );

        String creationTimestamp = safeText(
                rsJson.at("/metadata/creationTimestamp")
        );

        int desiredReplicas = rsJson.at("/spec/replicas").asInt(0);
        int readyReplicas = rsJson.at("/status/readyReplicas").asInt(0);

        List<String> images = extractImages(rsJson.at("/spec/template/spec/containers"));

        Map<String, String> annotations = sanitizeMap(
                jsonToMap(rsJson.at("/metadata/annotations"))
        );

        Map<String, String> labels = sanitizeMap(
                jsonToMap(rsJson.at("/metadata/labels"))
        );

        return new KubernetesReplicaSetRuntime(
                namespace,
                name,
                deploymentName,
                revision,
                creationTimestamp,
                desiredReplicas,
                readyReplicas,
                images,
                annotations,
                labels
        );
    }

    private List<KubernetesPodRuntime> collectPods(
            String context,
            String namespace,
            String selector
    ) {
        try {
            JsonNode podsJson = kubectlRunner.listPods(
                    context,
                    namespace,
                    selector
            );

            JsonNode items = podsJson.get("items");
            if (items == null || !items.isArray()) {
                return List.of();
            }

            return StreamSupport.stream(items.spliterator(), false)
                    .map(this::mapPod)
                    .collect(Collectors.toList());

        } catch (Exception exception) {
            return List.of();
        }
    }

    private KubernetesPodRuntime mapPod(JsonNode podJson) {
        String namespace = safeText(podJson.at("/metadata/namespace"));
        String podName = safeText(podJson.at("/metadata/name"));
        String phase = safeText(podJson.at("/status/phase"));
        String nodeName = safeText(podJson.at("/spec/nodeName"));
        String podIp = safeText(podJson.at("/status/podIP"));
        String startedAt = safeText(podJson.at("/status/startTime"));

        List<KubernetesContainerRuntime> containers = mapContainers(
                podJson.at("/spec/containers"),
                podJson.at("/status/containerStatuses")
        );

        Map<String, String> labels = sanitizeMap(
                jsonToMap(podJson.at("/metadata/labels"))
        );

        return new KubernetesPodRuntime(
                namespace,
                podName,
                phase,
                nodeName,
                podIp,
                startedAt,
                containers,
                labels
        );
    }

    private List<KubernetesContainerRuntime> mapContainers(
            JsonNode specContainers,
            JsonNode statusContainers
    ) {
        if (specContainers == null || !specContainers.isArray()) {
            return List.of();
        }

        Map<String, JsonNode> statusByName = new HashMap<>();
        if (statusContainers != null && statusContainers.isArray()) {
            for (JsonNode status : statusContainers) {
                String name = safeText(status.get("name"));
                if (name != null) {
                    statusByName.put(name, status);
                }
            }
        }

        List<KubernetesContainerRuntime> containers = new ArrayList<>();

        for (JsonNode container : specContainers) {
            String name = safeText(container.get("name"));
            String image = safeText(container.get("image"));

            ContainerImageParser.ParsedContainerImage parsed = null;
            if (image != null) {
                try {
                    parsed = imageParser.parse(image);
                } catch (Exception ignored) {
                }
            }

            JsonNode status = statusByName.get(name);

            String imageId = null;
            boolean ready = false;
            int restartCount = 0;

            if (status != null) {
                imageId = safeText(status.get("imageID"));
                ready = status.at("/ready").asBoolean(false);
                restartCount = status.at("/restartCount").asInt(0);
            }

            String imageDigest = null;
            if (imageId != null && imageId.contains("@sha256:")) {
                int digestIndex = imageId.indexOf("@sha256:");
                imageDigest = imageId.substring(digestIndex + 1);
            }

            containers.add(new KubernetesContainerRuntime(
                    name,
                    image,
                    parsed != null ? parsed.tag() : null,
                    parsed != null ? parsed.digest() : imageDigest,
                    imageId,
                    ready,
                    restartCount
            ));
        }

        return containers;
    }

    private List<String> extractImages(JsonNode containers) {
        if (containers == null || !containers.isArray()) {
            return List.of();
        }

        List<String> images = new ArrayList<>();
        for (JsonNode container : containers) {
            String image = safeText(container.get("image"));
            if (image != null) {
                images.add(image);
            }
        }

        return images;
    }

    private Map<String, String> jsonToMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                result.put(key, value.asText());
            }
        });

        return result;
    }

    private Map<String, String> sanitizeMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sanitized = new HashMap<>();
        map.forEach((key, value) -> {
            String sanitizedValue = evidenceSanitizer.sanitize(value);
            sanitized.put(key, sanitizedValue);
        });

        return sanitized;
    }

    private String buildSelector(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }

        return labels.entrySet().stream()
                .filter(e -> "app".equals(e.getKey())
                        || e.getKey().startsWith("app.kubernetes.io/"))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private String safeText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }

        return node.asText();
    }

    private List<DeploymentTarget> getDeploymentTargets(String applicationKey) {
        return List.of();
    }

    private static class DeploymentTarget {
        String namespace;
        String deploymentName;

        DeploymentTarget(String namespace, String deploymentName) {
            this.namespace = namespace;
            this.deploymentName = deploymentName;
        }
    }
}
