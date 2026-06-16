package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

@Component
public class ConfluenceHttpClient implements ConfluenceClient {

    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public ConfluenceHttpClient(
            ReplayFixProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConfluenceConnectivityResult connectivity() {
        var cfg = properties.getIntegrations().getConfluence();

        List<String> warnings = new ArrayList<>();

        boolean enabled = cfg.isEnabled();
        boolean baseUrlConfigured = cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank();
        boolean usernameConfigured = cfg.getUsername() != null && !cfg.getUsername().isBlank();
        boolean tokenConfigured = cfg.getToken() != null && !cfg.getToken().isBlank();

        if (!enabled) {
            warnings.add("Confluence integration is disabled");
            return new ConfluenceConnectivityResult(
                    false,
                    false,
                    baseUrlConfigured,
                    usernameConfigured,
                    tokenConfigured,
                    null,
                    null,
                    0,
                    warnings
            );
        }

        if (!baseUrlConfigured) {
            warnings.add("Base URL is not configured");
        }

        if (!usernameConfigured) {
            warnings.add("Username is not configured");
        }

        if (!tokenConfigured) {
            warnings.add("API token is not configured");
        }

        try {
            String endpoint = buildEndpoint("/wiki/api/v2/spaces?limit=1");

            int[] statusCode = {0};
            String[] contentType = {null};

            try {
                webClientBuilder
                        .build()
                        .get()
                        .uri(endpoint)
                        .headers(headers ->
                                headers.addAll(HttpSupport.headers(cfg)))
                        .retrieve()
                        .toEntity(String.class)
                        .map(entity -> {
                            statusCode[0] = entity.getStatusCode().value();
                            contentType[0] = entity.getHeaders().getContentType() != null
                                    ? entity.getHeaders().getContentType().toString()
                                    : null;
                            return entity.getBody();
                        })
                        .block(cfg.getTimeout());

            } catch (Exception ignored) {
            }

            boolean isHtml = isHtmlResponse(contentType[0]);

            if (isHtml) {
                warnings.add("Endpoint returned HTML, likely login page");
            }

            boolean success = !isHtml
                    && statusCode[0] == 200
                    && contentType[0] != null
                    && contentType[0].contains("application/json");

            String authenticatedMode = success ? "BASIC_API_TOKEN" : null;

            int visibleSpaceSampleCount = success ? 1 : 0;

            return new ConfluenceConnectivityResult(
                    success,
                    enabled,
                    baseUrlConfigured,
                    usernameConfigured,
                    tokenConfigured,
                    statusCode[0],
                    authenticatedMode,
                    visibleSpaceSampleCount,
                    warnings
            );

        } catch (Exception exception) {
            warnings.add("Connection failed: " + exception.getMessage());

            return new ConfluenceConnectivityResult(
                    false,
                    enabled,
                    baseUrlConfigured,
                    usernameConfigured,
                    tokenConfigured,
                    null,
                    null,
                    0,
                    warnings
            );
        }
    }

    @Override
    public ConfluenceSearchResponse search(ConfluenceSearchRequest request) {
        var cfg = properties.getIntegrations().getConfluence();

        if (!cfg.isEnabled()) {
            return new ConfluenceSearchResponse(
                    request.cql(),
                    0,
                    List.of(),
                    null,
                    List.of("Confluence integration is disabled")
            );
        }

        List<String> warnings = new ArrayList<>();

        try {
            String endpoint = buildEndpoint("/wiki/rest/api/search")
                    + "?cql=" + urlEncode(request.cql())
                    + "&limit=" + request.limit();

            JsonNode response = webClientBuilder
                    .build()
                    .get()
                    .uri(endpoint)
                    .headers(headers ->
                            headers.addAll(HttpSupport.headers(cfg)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            if (response == null) {
                warnings.add("Empty response from Confluence search");
                return new ConfluenceSearchResponse(
                        request.cql(),
                        0,
                        List.of(),
                        null,
                        warnings
                );
            }

            JsonNode results = response.get("results");
            int resultCount = response.path("totalSize").asInt(0);

            List<ConfluenceSearchHit> hits = new ArrayList<>();

            if (results != null && results.isArray()) {
                for (JsonNode item : results) {
                    String pageId = item.path("content").path("id").asText("");
                    String title = item.path("title").asText("");
                    String spaceKey = item.path("content").path("space").path("key").asText("");
                    String spaceName = item.path("content").path("space").path("name").asText("");
                    String url = item.path("url").asText("");
                    String excerpt = item.path("excerpt").asText("");
                    String lastModified = item.path("lastModified").asText("");
                    int versionNumber = item.path("content").path("version").path("number").asInt(0);
                    double apiScore = item.path("score").asDouble(0.0);

                    hits.add(new ConfluenceSearchHit(
                            pageId,
                            title,
                            spaceKey,
                            spaceName,
                            url,
                            excerpt,
                            lastModified,
                            versionNumber,
                            apiScore,
                            0,
                            List.of()
                    ));
                }
            }

            String nextCursor = response.path("_links").path("next").asText(null);

            return new ConfluenceSearchResponse(
                    request.cql(),
                    resultCount,
                    hits,
                    nextCursor,
                    warnings
            );

        } catch (Exception exception) {
            warnings.add("Search failed: " + exception.getMessage());

            return new ConfluenceSearchResponse(
                    request.cql(),
                    0,
                    List.of(),
                    null,
                    warnings
            );
        }
    }

    @Override
    public ConfluencePageDocument getPage(String pageId) {
        var cfg = properties.getIntegrations().getConfluence();

        List<String> warnings = new ArrayList<>();

        if (!cfg.isEnabled()) {
            warnings.add("Confluence integration is disabled");
            return createEmptyPage(pageId, warnings);
        }

        try {
            String endpoint = buildEndpoint("/wiki/api/v2/pages/" + pageId)
                    + "?body-format=storage";

            JsonNode response = webClientBuilder
                    .build()
                    .get()
                    .uri(endpoint)
                    .headers(headers ->
                            headers.addAll(HttpSupport.headers(cfg)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            if (response == null) {
                warnings.add("Empty response for page: " + pageId);
                return createEmptyPage(pageId, warnings);
            }

            String title = response.path("title").asText("");
            String spaceId = response.path("spaceId").asText("");
            String status = response.path("status").asText("");
            String webUrl = response.path("_links").path("webui").asText("");
            int versionNumber = response.path("version").path("number").asInt(0);
            String versionCreatedAt = response.path("version").path("createdAt").asText("");
            String bodyFormat = "storage";
            String bodyValue = response.path("body").path("storage").path("value").asText("");

            JsonNode labelsNode = response.path("metadata").path("labels").path("results");
            List<String> labels = new ArrayList<>();
            if (labelsNode != null && labelsNode.isArray()) {
                for (JsonNode label : labelsNode) {
                    labels.add(label.path("name").asText(""));
                }
            }

            return new ConfluencePageDocument(
                    pageId,
                    title,
                    spaceId,
                    "",
                    status,
                    webUrl,
                    versionNumber,
                    versionCreatedAt,
                    bodyFormat,
                    bodyValue,
                    bodyValue.length(),
                    false,
                    labels,
                    warnings
            );

        } catch (Exception exception) {
            warnings.add("Failed to fetch page: " + exception.getMessage());
            return createEmptyPage(pageId, warnings);
        }
    }

    private String buildEndpoint(String path) {
        var cfg = properties.getIntegrations().getConfluence();
        String baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");

        if (baseUrl.endsWith("/wiki")) {
            return baseUrl + path.substring("/wiki".length());
        }

        return baseUrl + path;
    }

    private boolean isHtmlResponse(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private ConfluencePageDocument createEmptyPage(String pageId, List<String> warnings) {
        return new ConfluencePageDocument(
                pageId,
                "",
                "",
                "",
                "",
                "",
                0,
                "",
                "",
                "",
                0,
                false,
                List.of(),
                warnings
        );
    }
}
