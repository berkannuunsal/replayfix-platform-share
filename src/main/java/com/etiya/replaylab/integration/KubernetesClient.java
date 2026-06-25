package com.etiya.replaylab.integration;

import com.etiya.replaylab.config.ReplayLabProperties.Target;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.IntegrationModels.ProvisionResult;
import com.etiya.replaylab.model.IntegrationModels.ReplayResult;

public interface KubernetesClient {
    ProvisionResult provision(ReplayCaseEntity replayCase, Target target);
    ReplayResult replay(ReplayCaseEntity replayCase, Target target);
    void cleanup(String namespace);
}
