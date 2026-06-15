package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.IntegrationModels.ProvisionResult;
import com.etiya.replayfix.model.IntegrationModels.ReplayResult;

public interface KubernetesClient {
    ProvisionResult provision(ReplayCaseEntity replayCase, Target target);
    ReplayResult replay(ReplayCaseEntity replayCase, Target target);
    void cleanup(String namespace);
}
