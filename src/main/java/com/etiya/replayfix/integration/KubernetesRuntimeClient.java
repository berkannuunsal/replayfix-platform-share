package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.KubernetesRuntimeInventory;

import java.util.UUID;

public interface KubernetesRuntimeClient {

    KubernetesRuntimeInventory collect(
            UUID caseId,
            String applicationKey
    );
}
