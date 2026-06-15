package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.KnowledgeResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Component
public class ConfluenceKnowledgeClient implements KnowledgeClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public ConfluenceKnowledgeClient(ReplayFixProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public List<KnowledgeResult> search(String query) {
        var cfg = properties.getIntegrations().getConfluence();
        if (!cfg.isEnabled()) return List.of();

        String cql = "text ~ \"" + query.replace("\"", "") + "\"";
        String uri = UriComponentsBuilder.fromPath("/wiki/rest/api/search")
                .queryParam("cql", cql)
                .queryParam("limit", 5)
                .build().toUriString();

        JsonNode node = webClientBuilder.baseUrl(cfg.getBaseUrl()).build().get()
                .uri(uri)
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        List<KnowledgeResult> results = new ArrayList<>();
        if (node == null) return results;

        for (JsonNode item : node.path("results")) {
            results.add(new KnowledgeResult(
                    "confluence",
                    item.path("title").asText(""),
                    item.path("excerpt").asText(""),
                    item.path("url").asText("")
            ));
        }
        return results;
    }
}
