package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.IntegrationModels.KnowledgeResult;
import java.util.List;

public interface KnowledgeClient {
    List<KnowledgeResult> search(String query);
}
