package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JiraHttpClient implements JiraClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public JiraHttpClient(ReplayFixProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        var cfg = properties.getIntegrations().getJira();

        if (!cfg.isEnabled()) {
            return new JiraIssue(
                issueKey,
                "Dry-run incident " + issueKey,
                "Jira integration is disabled.",
                Map.of()
            );
        }

        JsonNode node = webClientBuilder
            .baseUrl(cfg.getBaseUrl())
            .build()
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/rest/api/3/issue/{key}")
                .queryParam(
                    "fields",
                    "summary,status,priority,assignee,description"
                )
                .build(issueKey)
            )
            .headers(headers ->
                headers.addAll(HttpSupport.headers(cfg))
            )
            .retrieve()
            .onStatus(
                status -> status.isError(),
                response -> response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> new IllegalStateException(
                        "Jira request failed. HTTP "
                            + response.statusCode()
                            + " Response: "
                            + body
                    ))
            )
            .bodyToMono(JsonNode.class)
            .block(cfg.getTimeout());

        if (node == null) {
            throw new IllegalStateException(
                "Jira returned an empty response"
            );
        }

        JsonNode fields = node.path("fields");

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put(
            "status",
            fields.path("status").path("name").asText("")
        );
        extra.put(
            "priority",
            fields.path("priority").path("name").asText("")
        );
        extra.put(
            "assignee",
            fields.path("assignee").path("displayName").asText("")
        );

        return new JiraIssue(
            issueKey,
            fields.path("summary").asText(""),
            fields.path("description").toString(),
            extra
        );
    }

    @Override
    public void addComment(String issueKey, String text) {
        var cfg = properties.getIntegrations().getJira();
        if (!cfg.isEnabled()) return;

        Map<String, Object> body = Map.of(
                "body", Map.of(
                        "type", "doc",
                        "version", 1,
                        "content", new Object[]{
                                Map.of("type", "paragraph",
                                        "content", new Object[]{Map.of("type", "text", "text", text)})
                        }
                )
        );

        webClientBuilder.baseUrl(cfg.getBaseUrl()).build().post()
                .uri("/rest/api/3/issue/{key}/comment", issueKey)
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block(cfg.getTimeout());
    }
}
