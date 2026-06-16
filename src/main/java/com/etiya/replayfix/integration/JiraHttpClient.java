package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import com.etiya.replayfix.model.JiraCommentPublishResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JiraHttpClient implements JiraClient {
    private static final Logger log = LoggerFactory.getLogger(JiraHttpClient.class);
    
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

    @Override
    public JiraCommentPublishResponse addCommentAdf(String issueKey, JsonNode adfBody) {
        var cfg = properties.getIntegrations().getJira();
        List<String> warnings = new ArrayList<>();

        if (!cfg.isEnabled()) {
            log.warn("Jira integration is disabled, cannot publish comment");
            return new JiraCommentPublishResponse(
                    false,
                    null,
                    issueKey,
                    null,
                    null,
                    null,
                    List.of("Jira integration is disabled")
            );
        }

        Map<String, Object> requestBody = Map.of("body", adfBody);

        try {
            JsonNode response = webClientBuilder
                    .baseUrl(cfg.getBaseUrl())
                    .build()
                    .post()
                    .uri("/rest/api/3/issue/{key}/comment", issueKey)
                    .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            response2 -> response2
                                    .bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(errorBody -> new IllegalStateException(
                                            "Jira comment request failed. HTTP "
                                                    + response2.statusCode()
                                                    + " Response: "
                                                    + errorBody
                                    ))
                    )
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            if (response == null) {
                return new JiraCommentPublishResponse(
                        false,
                        null,
                        issueKey,
                        null,
                        null,
                        null,
                        List.of("Jira returned null response")
                );
            }

            String commentId = response.path("id").asText(null);
            String selfUrl = response.path("self").asText(null);
            String createdAt = response.path("created").asText(null);

            log.info("Successfully published Jira comment {} to issue {}", commentId, issueKey);

            return new JiraCommentPublishResponse(
                    true,
                    201,
                    issueKey,
                    commentId,
                    selfUrl,
                    createdAt,
                    warnings
            );

        } catch (Exception e) {
            log.error("Failed to publish Jira comment to issue {}: {}", issueKey, e.getMessage());
            
            return new JiraCommentPublishResponse(
                    false,
                    null,
                    issueKey,
                    null,
                    null,
                    null,
                    List.of("Failed to publish comment: " + e.getMessage())
            );
        }
    }
}
