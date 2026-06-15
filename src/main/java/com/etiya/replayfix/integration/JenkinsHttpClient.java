package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.BuildResult;
import com.etiya.replayfix.model.JenkinsConnectionTestResult;
import com.etiya.replayfix.model.JenkinsJobSnapshot;
import com.etiya.replayfix.model.JenkinsJobSnapshot.BuildSummary;
import com.etiya.replayfix.model.JenkinsJobSnapshot.ParameterDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class JenkinsHttpClient implements JenkinsClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public JenkinsHttpClient(ReplayFixProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public BuildResult runValidation(Map<String, String> parameters) {
        var cfg = properties.getIntegrations().getJenkins();
        if (!cfg.isEnabled()) {
            return new BuildResult("SUCCESS", "dry-run://jenkins/1", 1, 0, "{}");
        }

        UriComponentsBuilder uri = UriComponentsBuilder
                .fromPath("/job/" + cfg.getJobName() + "/buildWithParameters");
        parameters.forEach(uri::queryParam);

        var response = webClientBuilder.baseUrl(cfg.getBaseUrl()).build().post()
                .uri(uri.build().toUriString())
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .retrieve()
                .toBodilessEntity()
                .block(cfg.getTimeout());

        String queueUrl = response == null || response.getHeaders().getLocation() == null
                ? ""
                : response.getHeaders().getLocation().toString();

        if (queueUrl.isBlank()) {
            return new BuildResult("QUEUED", "", 0, 0, "{}");
        }

        long deadline = System.nanoTime() + cfg.getMaxWait().toNanos();
        String buildUrl = "";

        while (System.nanoTime() < deadline && buildUrl.isBlank()) {
            sleep(cfg.getPollInterval());
            JsonNode queue = webClientBuilder.build().get()
                    .uri(queueUrl + "api/json")
                    .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());
            if (queue != null) buildUrl = queue.path("executable").path("url").asText("");
        }

        if (buildUrl.isBlank()) {
            return new BuildResult("TIMEOUT", queueUrl, 0, 0, "{}");
        }

        while (System.nanoTime() < deadline) {
            sleep(cfg.getPollInterval());
            JsonNode build = webClientBuilder.build().get()
                    .uri(buildUrl + "api/json")
                    .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            if (build != null && !build.path("building").asBoolean(true)) {
                String status = build.path("result").asText("UNKNOWN");
                return new BuildResult(
                        status,
                        buildUrl,
                        0,
                        "SUCCESS".equals(status) ? 0 : 1,
                        build.toString()
                );
            }
        }

        return new BuildResult("TIMEOUT", buildUrl, 0, 0, "{}");
    }

    @Override
    public JenkinsConnectionTestResult testConnection() {
        var cfg = properties.getIntegrations().getJenkins();

        if (!cfg.isEnabled()) {
            return new JenkinsConnectionTestResult(
                    false,
                    "",
                    cfg.getBaseUrl(),
                    "Jenkins integration is disabled."
            );
        }

        try {
            JsonNode response = getJson(
                    normalizeUrl(cfg.getBaseUrl())
                            + "/whoAmI/api/json"
            );

            boolean authenticated = response.path("authenticated")
                    .asBoolean(false);

            String user = response.path("name")
                    .asText("");

            return new JenkinsConnectionTestResult(
                    authenticated,
                    user,
                    cfg.getBaseUrl(),
                    authenticated
                            ? "Jenkins connection successful."
                            : "Jenkins responded but user is not authenticated."
            );

        } catch (Exception exception) {
            return new JenkinsConnectionTestResult(
                    false,
                    "",
                    cfg.getBaseUrl(),
                    rootCauseMessage(exception)
            );
        }
    }

    @Override
    public JenkinsJobSnapshot readJob(String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Jenkins job URL is empty."
            );
        }

        String tree = "name,url,buildable,inQueue,nextBuildNumber,"
                + "lastBuild[number,result,timestamp,duration,url],"
                + "lastSuccessfulBuild[number,result,timestamp,duration,url],"
                + "lastFailedBuild[number,result,timestamp,duration,url],"
                + "actions[parameterDefinitions["
                + "name,type,defaultParameterValue[value]]]";

        JsonNode response = getJson(
                normalizeUrl(jobUrl)
                        + "/api/json?tree="
                        + tree
        );

        return new JenkinsJobSnapshot(
                response.path("name").asText(""),
                response.path("url").asText(jobUrl),
                response.path("buildable").asBoolean(false),
                response.path("inQueue").asBoolean(false),
                nullableInteger(
                        response.get("nextBuildNumber")
                ),
                parseBuild(response.get("lastBuild")),
                parseBuild(
                        response.get("lastSuccessfulBuild")
                ),
                parseBuild(
                        response.get("lastFailedBuild")
                ),
                parseParameters(response.path("actions"))
        );
    }

    private JsonNode getJson(String url) {
        var cfg = properties.getIntegrations().getJenkins();

        JsonNode response = webClientBuilder
                .build()
                .get()
                .uri(URI.create(url))
                .headers(headers -> {
                    headers.setAccept(
                            List.of(MediaType.APPLICATION_JSON)
                    );
                    headers.setBasicAuth(
                            cfg.getUsername(),
                            cfg.getToken(),
                            StandardCharsets.UTF_8
                    );
                })
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse
                                .bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body ->
                                        new IllegalStateException(
                                                "Jenkins request failed. HTTP "
                                                        + clientResponse.statusCode()
                                                        + " Response: "
                                                        + truncate(body, 2_000)
                                        )
                                )
                )
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        if (response == null) {
            throw new IllegalStateException(
                    "Jenkins returned an empty response."
            );
        }

        return response;
    }

    private BuildSummary parseBuild(JsonNode build) {
        if (build == null
                || build.isNull()
                || build.isMissingNode()) {
            return null;
        }

        return new BuildSummary(
                nullableInteger(build.get("number")),
                nullableText(build.get("result")),
                nullableLong(build.get("timestamp")),
                nullableLong(build.get("duration")),
                nullableText(build.get("url"))
        );
    }

    private List<ParameterDefinition> parseParameters(
            JsonNode actions
    ) {
        List<ParameterDefinition> parameters =
                new ArrayList<>();

        if (actions == null || !actions.isArray()) {
            return parameters;
        }

        for (JsonNode action : actions) {
            JsonNode definitions =
                    action.path("parameterDefinitions");

            if (!definitions.isArray()) {
                continue;
            }

            for (JsonNode definition : definitions) {
                parameters.add(
                        new ParameterDefinition(
                                definition.path("name")
                                        .asText(""),
                                definition.path("type")
                                        .asText(""),
                                definition.path(
                                        "defaultParameterValue"
                                )
                                        .path("value")
                                        .asText("")
                        )
                );
            }
        }

        return parameters;
    }

    private Integer nullableInteger(JsonNode node) {
        return node == null
                || node.isNull()
                || node.isMissingNode()
                ? null
                : node.asInt();
    }

    private Long nullableLong(JsonNode node) {
        return node == null
                || node.isNull()
                || node.isMissingNode()
                ? null
                : node.asLong();
    }

    private String nullableText(JsonNode node) {
        return node == null
                || node.isNull()
                || node.isMissingNode()
                ? null
                : node.asText();
    }

    private String normalizeUrl(String value) {
        return value == null
                ? ""
                : value.replaceAll("/+$", "");
    }

    private String rootCauseMessage(
            Throwable throwable
    ) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName()
                + ": "
                + root.getMessage();
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
