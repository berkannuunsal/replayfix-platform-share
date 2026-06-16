package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.integration.KubernetesRuntimeClient;
import com.etiya.replayfix.model.KubernetesRuntimeInventory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class KubernetesRuntimeInventoryService {

    private static final String OUTPUT_SOURCE =
            "kubernetes-runtime-inventory";

    private final KubernetesRuntimeClient runtimeClient;
    private final EvidenceService evidenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public KubernetesRuntimeInventoryService(
            KubernetesRuntimeClient runtimeClient,
            EvidenceService evidenceService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.runtimeClient = runtimeClient;
        this.evidenceService = evidenceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public KubernetesRuntimeInventory collect(UUID caseId) {
        String applicationKey = determineApplicationKey(caseId);

        KubernetesRuntimeInventory inventory = runtimeClient.collect(
                caseId,
                applicationKey
        );

        try {
            String json = objectMapper.writeValueAsString(inventory);

            evidenceService.save(
                    caseId,
                    EvidenceType.REPLAY_OUTPUT,
                    OUTPUT_SOURCE,
                    json,
                    true
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot save Kubernetes runtime inventory evidence.",
                    exception
            );
        }

        auditService.record(
                caseId,
                "KUBERNETES_RUNTIME_INVENTORY_COLLECTED",
                "replayfix-platform",
                "context=" + inventory.context()
                        + ", deployments=" + inventory.deployments().size()
                        + ", evidenceLevel=" + inventory.clusterEvidenceLevel()
        );

        return inventory;
    }

    private String determineApplicationKey(UUID caseId) {
        return "backend";
    }
}
