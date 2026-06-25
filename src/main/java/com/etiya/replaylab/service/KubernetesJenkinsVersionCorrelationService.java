package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.EvidenceEntity;
import com.etiya.replaylab.domain.EvidenceType;
import com.etiya.replaylab.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class KubernetesJenkinsVersionCorrelationService {

    private static final String OUTPUT_SOURCE =
            "kubernetes-jenkins-version-correlation";

    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public KubernetesJenkinsVersionCorrelationService(
            EvidenceService evidenceService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.evidenceService = evidenceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public KubernetesJenkinsVersionCorrelation correlate(UUID caseId) {
        KubernetesRuntimeInventory inventory = getLatestInventory(caseId);

        JenkinsIncidentVersionValidation jenkinsValidation = getJenkinsValidation(caseId);

        String jenkinsCommitSha = jenkinsValidation != null
                ? jenkinsValidation.jenkinsCommitSha()
                : null;

        Integer jenkinsBuildNumber = jenkinsValidation != null
                && jenkinsValidation.buildAtIncident() != null
                ? jenkinsValidation.buildAtIncident().buildNumber()
                : null;

        String jenkinsBuildTimestamp = jenkinsValidation != null
                && jenkinsValidation.buildAtIncident() != null
                && jenkinsValidation.buildAtIncident().timestamp() != null
                ? String.valueOf(jenkinsValidation.buildAtIncident().timestamp())
                : null;

        List<String> runtimeImages = extractRuntimeImages(inventory);
        List<String> runtimeImageDigests = extractRuntimeImageDigests(inventory);
        List<String> replicaSetRevisions = extractReplicaSetRevisions(inventory);

        List<String> matchedSignals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        RuntimeVersionCorrelationStatus status = determineStatus(
                jenkinsCommitSha,
                jenkinsBuildNumber,
                inventory,
                runtimeImages,
                runtimeImageDigests,
                replicaSetRevisions,
                matchedSignals,
                warnings
        );

        double confidence = calculateConfidence(status, matchedSignals);

        KubernetesJenkinsVersionCorrelation correlation =
                new KubernetesJenkinsVersionCorrelation(
                        caseId,
                        inventory.applicationKey(),
                        jenkinsValidation != null ? jenkinsValidation.repositorySlug() : null,
                        jenkinsCommitSha,
                        jenkinsBuildNumber,
                        jenkinsBuildTimestamp,
                        runtimeImages,
                        runtimeImageDigests,
                        replicaSetRevisions,
                        status,
                        confidence,
                        matchedSignals,
                        warnings
                );

        saveEvidence(caseId, correlation);

        auditService.record(
                caseId,
                "KUBERNETES_JENKINS_VERSION_CORRELATED",
                "replaylab-platform",
                "status=" + status + ", confidence=" + confidence
        );

        return correlation;
    }

    private RuntimeVersionCorrelationStatus determineStatus(
            String jenkinsCommitSha,
            Integer jenkinsBuildNumber,
            KubernetesRuntimeInventory inventory,
            List<String> runtimeImages,
            List<String> runtimeImageDigests,
            List<String> replicaSetRevisions,
            List<String> matchedSignals,
            List<String> warnings
    ) {
        if (jenkinsCommitSha == null && jenkinsBuildNumber == null) {
            warnings.add("No Jenkins validation evidence found.");
            return RuntimeVersionCorrelationStatus.INSUFFICIENT_EVIDENCE;
        }

        if (runtimeImages.isEmpty()) {
            warnings.add("No runtime images found in Kubernetes inventory.");
            return RuntimeVersionCorrelationStatus.INSUFFICIENT_EVIDENCE;
        }

        for (String image : runtimeImages) {
            if (jenkinsCommitSha != null && image.contains(jenkinsCommitSha)) {
                matchedSignals.add("Image tag contains exact commit SHA: " + image);
                return RuntimeVersionCorrelationStatus.EXACT_COMMIT_MATCH;
            }
        }

        for (KubernetesDeploymentRuntime deployment : inventory.deployments()) {
            String annotationCommit = deployment.annotations().get("commit-sha");
            if (jenkinsCommitSha != null && jenkinsCommitSha.equals(annotationCommit)) {
                matchedSignals.add("Deployment annotation contains exact commit SHA");
                return RuntimeVersionCorrelationStatus.EXACT_COMMIT_MATCH;
            }

            for (KubernetesReplicaSetRuntime rs : deployment.replicaSets()) {
                String rsAnnotationCommit = rs.annotations().get("commit-sha");
                if (jenkinsCommitSha != null && jenkinsCommitSha.equals(rsAnnotationCommit)) {
                    matchedSignals.add("ReplicaSet annotation contains exact commit SHA");
                    return RuntimeVersionCorrelationStatus.EXACT_COMMIT_MATCH;
                }
            }
        }

        if (jenkinsBuildNumber != null) {
            for (String image : runtimeImages) {
                if (image.contains(String.valueOf(jenkinsBuildNumber))) {
                    matchedSignals.add("Image tag contains build number: " + jenkinsBuildNumber);
                    return RuntimeVersionCorrelationStatus.IMAGE_TAG_BUILD_MATCH;
                }
            }
        }

        if (!runtimeImageDigests.isEmpty()) {
            matchedSignals.add("Runtime image digests available but cannot verify against Jenkins");
            return RuntimeVersionCorrelationStatus.IMAGE_DIGEST_ONLY;
        }

        if (!replicaSetRevisions.isEmpty()) {
            matchedSignals.add("ReplicaSet history available but inconclusive");
            return RuntimeVersionCorrelationStatus.REPLICASET_TIME_SUPPORTS_BUILD;
        }

        if (!inventory.deployments().isEmpty()) {
            warnings.add("Runtime inventory found but no version correlation signals");
            return RuntimeVersionCorrelationStatus.CURRENT_RUNTIME_ONLY;
        }

        return RuntimeVersionCorrelationStatus.INSUFFICIENT_EVIDENCE;
    }

    private double calculateConfidence(
            RuntimeVersionCorrelationStatus status,
            List<String> matchedSignals
    ) {
        return switch (status) {
            case EXACT_COMMIT_MATCH -> 0.95;
            case IMAGE_TAG_BUILD_MATCH -> 0.85;
            case REPLICASET_TIME_SUPPORTS_BUILD -> 0.70;
            case IMAGE_DIGEST_ONLY -> 0.55;
            case CURRENT_RUNTIME_ONLY -> 0.40;
            case MISMATCH -> 0.10;
            case INSUFFICIENT_EVIDENCE -> 0.0;
        };
    }

    private List<String> extractRuntimeImages(KubernetesRuntimeInventory inventory) {
        List<String> images = new ArrayList<>();

        for (KubernetesDeploymentRuntime deployment : inventory.deployments()) {
            images.addAll(deployment.images());

            for (KubernetesPodRuntime pod : deployment.pods()) {
                for (KubernetesContainerRuntime container : pod.containers()) {
                    if (container.image() != null) {
                        images.add(container.image());
                    }
                }
            }
        }

        return images.stream().distinct().toList();
    }

    private List<String> extractRuntimeImageDigests(KubernetesRuntimeInventory inventory) {
        List<String> digests = new ArrayList<>();

        for (KubernetesDeploymentRuntime deployment : inventory.deployments()) {
            for (KubernetesPodRuntime pod : deployment.pods()) {
                for (KubernetesContainerRuntime container : pod.containers()) {
                    if (container.imageDigest() != null) {
                        digests.add(container.imageDigest());
                    }
                }
            }
        }

        return digests.stream().distinct().toList();
    }

    private List<String> extractReplicaSetRevisions(KubernetesRuntimeInventory inventory) {
        List<String> revisions = new ArrayList<>();

        for (KubernetesDeploymentRuntime deployment : inventory.deployments()) {
            for (KubernetesReplicaSetRuntime rs : deployment.replicaSets()) {
                if (rs.revision() != null) {
                    revisions.add(rs.revision());
                }
            }
        }

        return revisions.stream().distinct().toList();
    }

    private KubernetesRuntimeInventory getLatestInventory(UUID caseId) {
        EvidenceEntity evidence = evidenceService.list(caseId)
                .stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.REPLAY_OUTPUT)
                .filter(e -> "kubernetes-runtime-inventory".equals(e.getSource()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException(
                        "Kubernetes runtime inventory not found for case: " + caseId
                ));

        try {
            return objectMapper.readValue(
                    evidence.getContentText(),
                    KubernetesRuntimeInventory.class
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot parse Kubernetes runtime inventory.",
                    exception
            );
        }
    }

    private JenkinsIncidentVersionValidation getJenkinsValidation(UUID caseId) {
        return evidenceService.list(caseId)
                .stream()
                .filter(e -> e.getEvidenceType() == EvidenceType.JENKINS_BUILD_CONTEXT)
                .filter(e -> "jenkins-incident-version-validator".equals(e.getSource()))
                .reduce((first, second) -> second)
                .map(evidence -> {
                    try {
                        return objectMapper.readValue(
                                evidence.getContentText(),
                                JenkinsIncidentVersionValidation.class
                        );
                    } catch (Exception exception) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private void saveEvidence(
            UUID caseId,
            KubernetesJenkinsVersionCorrelation correlation
    ) {
        try {
            String json = objectMapper.writeValueAsString(correlation);

            evidenceService.save(
                    caseId,
                    EvidenceType.INCIDENT_VERSION,
                    OUTPUT_SOURCE,
                    json,
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Kubernetes-Jenkins version correlation evidence.",
                    exception
            );
        }
    }
}
