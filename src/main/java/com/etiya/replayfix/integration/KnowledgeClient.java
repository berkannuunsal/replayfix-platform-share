package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.KnowledgeResult;
import java.util.List;

public interface KnowledgeClient {
    List<KnowledgeResult> search(String query);
}
