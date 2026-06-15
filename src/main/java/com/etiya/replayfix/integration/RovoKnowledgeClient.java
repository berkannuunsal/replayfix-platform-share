package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.KnowledgeResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RovoKnowledgeClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public RovoKnowledgeClient(
            ReplayFixProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public List<KnowledgeResult> search(String query) {
        var cfg = properties.getIntegrations().getRovo();
        if (!cfg.isEnabled()) return List.of();

        JsonNode node = webClientBuilder.baseUrl(cfg.getBaseUrl()).build()
                .post()
                .uri(cfg.getApiPath())
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query, "limit", 5))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        List<KnowledgeResult> results = new ArrayList<>();
        if (node == null) return results;

        JsonNode items = node.has("results")
                ? node.path("results")
                : node.path("items");

        for (JsonNode item : items) {
            results.add(new KnowledgeResult(
                    "rovo",
                    item.path("title").asText(""),
                    item.has("content")
                            ? item.path("content").asText("")
                            : item.path("excerpt").asText(""),
                    item.path("url").asText("")
            ));
        }
        return results;
    }
}
