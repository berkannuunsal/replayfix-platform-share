package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.KubernetesRuntimeInventory;

import java.util.UUID;

public interface KubernetesRuntimeClient {

    KubernetesRuntimeInventory collect(
            UUID caseId,
            String applicationKey
    );
}
