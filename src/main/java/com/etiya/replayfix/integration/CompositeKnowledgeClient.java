package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.IntegrationModels.KnowledgeResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Primary
public class CompositeKnowledgeClient implements KnowledgeClient {
    private final ConfluenceKnowledgeClient confluence;
    private final RovoKnowledgeClient rovo;

    public CompositeKnowledgeClient(
            ConfluenceKnowledgeClient confluence,
            RovoKnowledgeClient rovo
    ) {
        this.confluence = confluence;
        this.rovo = rovo;
    }

    @Override
    public List<KnowledgeResult> search(String query) {
        List<KnowledgeResult> results = new ArrayList<>();
        results.addAll(confluence.search(query));
        results.addAll(rovo.search(query));
        return results;
    }
}
