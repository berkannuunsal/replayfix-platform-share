package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import com.etiya.replayfix.model.IntegrationModels.JiraIssueCreateResult;
import com.etiya.replayfix.model.JiraCommentPublishResponse;
import com.etiya.replayfix.service.JiraAdfTextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JiraHttpClient implements JiraClient {
    private static final Logger log = LoggerFactory.getLogger(JiraHttpClient.class);
    
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final JiraAdfTextExtractor adfTextExtractor;

    public JiraHttpClient(
            ReplayFixProperties properties,
            WebClient.Builder webClientBuilder,
            JiraAdfTextExtractor adfTextExtractor
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.adfTextExtractor = adfTextExtractor;
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
    public JiraIssueCreateResult createIssue(Map<String, Object> payload) {
        var cfg = properties.getIntegrations().getJira();
        if (!cfg.isEnabled()) {
            return new JiraIssueCreateResult(
                    false,
                    null,
                    null,
                    0,
                    List.of("Jira integration is disabled")
            );
        }

        try {
            JsonNode response = webClientBuilder
                    .baseUrl(cfg.getBaseUrl())
                    .build()
                    .post()
                    .uri("/rest/api/3/issue")
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> clientResponse
                                    .bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body -> new IllegalStateException(
                                            "Jira issue create failed. HTTP "
                                                    + clientResponse.statusCode()
                                                    + " Response: "
                                                    + truncate(body, 2_000)
                                    ))
                    )
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            if (response == null) {
                return new JiraIssueCreateResult(
                        false,
                        null,
                        null,
                        0,
                        List.of("Jira returned null response")
                );
            }

            return new JiraIssueCreateResult(
                    true,
                    response.path("key").asText(""),
                    response.path("self").asText(""),
                    201,
                    List.of()
            );
        } catch (Exception exception) {
            log.error(
                    "Failed to create Jira issue: {}",
                    exception.getMessage()
            );
            return new JiraIssueCreateResult(
                    false,
                    null,
                    null,
                    0,
                    List.of("Failed to create Jira issue: "
                            + truncate(exception.getMessage(), 500))
            );
        }
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

    @Override
    public List<IntegrationModels.JiraComment> getComments(String issueKey) {
        var cfg = properties.getIntegrations().getJira();
        if (!cfg.isEnabled()) {
            log.warn("Jira integration is disabled");
            return List.of();
        }

        List<IntegrationModels.JiraComment> allComments = new ArrayList<>();
        int startAt = 0;
        int maxResults = 100;
        int totalFetched = 0;
        int pageCount = 0;

        try {
            while (true) {
                pageCount++;
                final int currentStartAt = startAt; // Make effectively final for lambda
                
                JsonNode response = webClientBuilder
                        .baseUrl(cfg.getBaseUrl())
                        .build()
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/rest/api/3/issue/{key}/comment")
                                .queryParam("startAt", currentStartAt)
                                .queryParam("maxResults", maxResults)
                                .build(issueKey))
                        .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(cfg.getTimeout());

                if (response == null || !response.has("comments")) {
                    break;
                }

                JsonNode commentsArray = response.get("comments");
                if (!commentsArray.isArray() || commentsArray.isEmpty()) {
                    break;
                }

                int pageSize = 0;
                for (JsonNode commentNode : commentsArray) {
                    String id = commentNode.path("id").asText(null);
                    String author = commentNode.path("author").path("displayName").asText("Unknown");
                    String createdStr = commentNode.path("created").asText(null);
                    String body = extractCommentBody(commentNode);

                    Instant created = null;
                    if (createdStr != null) {
                        try {
                            created = Instant.parse(createdStr);
                        } catch (Exception e) {
                            log.warn("Failed to parse created timestamp: {}", createdStr);
                        }
                    }

                    allComments.add(new IntegrationModels.JiraComment(id, author, created, body));
                    pageSize++;
                }

                totalFetched += pageSize;
                log.debug("Fetched page {} with {} comments for issue {}", pageCount, pageSize, issueKey);

                // Check if there are more comments
                int total = response.path("total").asInt(0);
                if (totalFetched >= total) {
                    break;
                }

                startAt += maxResults;
                
                // Safety limit to prevent infinite loops
                if (pageCount >= 50) {
                    log.warn("Reached maximum page limit (50) for issue {}", issueKey);
                    break;
                }
            }

            log.info("Retrieved {} comments across {} pages from Jira issue {}", allComments.size(), pageCount, issueKey);
            return allComments;

        } catch (Exception e) {
            log.error("Failed to fetch comments for issue {}: {}", issueKey, e.getMessage());
            return allComments; // Return what we have so far
        }
    }

    private String extractCommentBody(JsonNode commentNode) {
        JsonNode bodyNode = commentNode.get("body");
        if (bodyNode == null) {
            return "";
        }

        // Plain text body
        if (bodyNode.isTextual()) {
            return bodyNode.asText();
        }

        if (bodyNode.isObject()) {
            return adfTextExtractor.extract(bodyNode.toString());
        }

        return bodyNode.toString();
    }

    private void extractTextFromAdfNodes(JsonNode nodes, StringBuilder text) {
        if (nodes == null) {
            return;
        }
        
        if (!nodes.isArray()) {
            return;
        }

        for (JsonNode node : nodes) {
            String nodeType = node.path("type").asText("");

            switch (nodeType) {
                case "doc":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    break;

                case "paragraph":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    text.append("\n");
                    break;

                case "heading":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    text.append("\n");
                    break;

                case "codeBlock":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    text.append("\n");
                    break;

                case "panel":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    text.append("\n");
                    break;

                case "blockquote":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    text.append("\n");
                    break;

                case "bulletList":
                case "orderedList":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    break;

                case "listItem":
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    text.append("\n");
                    break;

                case "text":
                    text.append(node.path("text").asText(""));
                    break;

                case "hardBreak":
                    text.append("\n");
                    break;

                case "rule":
                    text.append("\n---\n");
                    break;

                default:
                    // Fallback: if it has content, try to extract recursively
                    if (node.has("content")) {
                        extractTextFromAdfNodes(node.get("content"), text);
                    }
                    break;
            }
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}
