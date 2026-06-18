package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.BuildResult;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JenkinsHttpClient implements JenkinsClient {

    private record JenkinsBuildReference(
            int number,
            String result,
            long timestamp,
            String url
    ) {
    }

    private static final Pattern CHECKOUT_REVISION_PATTERN =
            Pattern.compile(
                    "Checking out Revision\\s+([0-9a-fA-F]{7,40})"
            );

    private static final Pattern COMMIT_MESSAGE_PATTERN =
            Pattern.compile(
                    "Commit message:\\s*\"([^\"]+)\""
            );

    private static final Pattern TEST_SUMMARY_PATTERN =
            Pattern.compile(
                    "Tests run:\\s*(\\d+),\\s*"
                            + "Failures:\\s*(\\d+),\\s*"
                            + "Errors:\\s*(\\d+),\\s*"
                            + "Skipped:\\s*(\\d+)"
            );

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

    @Override
    public JenkinsBuildSnapshot readLastSuccessfulBuild(
            String jobUrl
    ) {
        if (jobUrl == null || jobUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Jenkins job URL is empty."
            );
        }

        return readBuildSnapshot(
                jobUrl,
                normalizeUrl(jobUrl)
                        + "/lastSuccessfulBuild"
        );
    }

    @Override
    public JenkinsBuildSnapshot readBuildAtOrBefore(
            String jobUrl,
            Instant incidentTime
    ) {
        if (jobUrl == null || jobUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Jenkins job URL is empty."
            );
        }

        if (incidentTime == null) {
            throw new IllegalArgumentException(
                    "Incident time is empty."
            );
        }

        URI uri = UriComponentsBuilder
                .fromUriString(
                        normalizeUrl(jobUrl) + "/api/json"
                )
                .queryParam(
                        "tree",
                        "builds[number,result,timestamp,url]{0,200}"
                )
                .build()
                .encode()
                .toUri();

        JsonNode response = getJson(uri);

        JenkinsBuildReference selected =
                toBuildReferences(
                        response.path("builds")
                )
                        .stream()
                        .filter(build ->
                                build.timestamp()
                                        <= incidentTime.toEpochMilli()
                        )
                        .filter(build ->
                                build.result() != null
                                        && !build.result().isBlank()
                        )
                        .max(
                                Comparator.comparingLong(
                                        JenkinsBuildReference::timestamp
                                )
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "No completed Jenkins build was found "
                                                + "at or before incident time: "
                                                + incidentTime
                                )
                        );

        return readBuildByNumber(
                jobUrl,
                selected.number()
        );
    }

    private JenkinsBuildSnapshot readBuildSnapshot(
            String jobUrl,
            String buildUrl
    ) {
        // Use bounded tree query to limit response size
        String tree = "number,result,timestamp,duration,url,"
                + "actions[lastBuiltRevision[SHA1],parameters[name,value]],changeSet[items[commitId,msg]],"
                + "artifacts[fileName,relativePath]";
        
        JsonNode response =
                getJson(buildUrl + "/api/json?tree=" + tree);

        String consoleText =
                getText(buildUrl + "/consoleText");

        String commitSha =
                findCommitSha(response);

        String metadataSource =
                commitSha.isBlank()
                        ? "NOT_FOUND"
                        : "JENKINS_JSON";

        if (commitSha.isBlank()) {
            commitSha =
                    findFirstGroup(
                            CHECKOUT_REVISION_PATTERN,
                            consoleText
                    );

            if (!commitSha.isBlank()) {
                metadataSource = "CONSOLE_TEXT";
            }
        }

        String commitMessage =
                findCommitMessage(response);

        if (commitMessage.isBlank()) {
            commitMessage =
                    findFirstGroup(
                            COMMIT_MESSAGE_PATTERN,
                            consoleText
                    );
        }

        return new JenkinsBuildSnapshot(
                extractJobName(jobUrl),
                nullableInteger(response.get("number")),
                response.path("result").asText(""),
                nullableLong(response.get("timestamp")),
                nullableLong(response.get("duration")),
                response.path("url").asText(buildUrl),
                commitSha,
                commitMessage,
                metadataSource,
                parseLastTestSummary(consoleText),
                parseBuildParameters(
                        response.path("actions")
                ),
                parseArtifacts(
                        response.path("artifacts")
                )
        );
    }

    private List<JenkinsBuildReference>
    toBuildReferences(
            JsonNode buildsNode
    ) {
        List<JenkinsBuildReference> builds =
                new ArrayList<>();

        if (buildsNode == null
                || !buildsNode.isArray()) {
            return builds;
        }

        for (JsonNode build : buildsNode) {
            builds.add(
                    new JenkinsBuildReference(
                            build.path("number").asInt(),
                            build.path("result").asText(""),
                            build.path("timestamp").asLong(),
                            build.path("url").asText("")
                    )
            );
        }

        return builds;
    }

    private JenkinsBuildSnapshot readBuildByNumber(
            String jobUrl,
            int buildNumber
    ) {
        return readBuildSnapshot(
                jobUrl,
                normalizeUrl(jobUrl) + "/" + buildNumber
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

    private JsonNode getJson(URI uri) {
        var cfg =
                properties.getIntegrations()
                        .getJenkins();

        JsonNode response =
                webClientBuilder
                        .build()
                        .get()
                        .uri(uri)
                        .headers(headers ->
                                headers.setBasicAuth(
                                        cfg.getUsername(),
                                        cfg.getToken(),
                                        StandardCharsets.UTF_8
                                )
                        )
                        .retrieve()
                        .onStatus(
                                status -> status.isError(),
                                clientResponse ->
                                        clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(body ->
                                                        new IllegalStateException(
                                                                "Jenkins request failed. HTTP "
                                                                        + clientResponse.statusCode()
                                                                        + " Response: "
                                                                        + truncate(body, 2000)
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

    private Map<String, String> parseBuildParameters(
            JsonNode actions
    ) {
        Map<String, String> parameters =
                new LinkedHashMap<>();

        if (actions == null || !actions.isArray()) {
            return parameters;
        }

        for (JsonNode action : actions) {
            JsonNode values =
                    action.path("parameters");

            if (!values.isArray()) {
                continue;
            }

            for (JsonNode parameter : values) {
                String name =
                        parameter.path("name")
                                .asText("");

                if (!name.isBlank()) {
                    parameters.put(
                            name,
                            parameter.path("value")
                                    .asText("")
                    );
                }
            }
        }

        return parameters;
    }

    private String findCommitSha(JsonNode response) {
        for (JsonNode action :
                response.path("actions")) {

            String sha =
                    action.path("lastBuiltRevision")
                            .path("SHA1")
                            .asText("");

            if (!sha.isBlank()) {
                return sha;
            }
        }

        String changeSetCommit =
                response.path("changeSet")
                        .path("items")
                        .path(0)
                        .path("commitId")
                        .asText("");

        return changeSetCommit;
    }

    private String extractJobName(String jobUrl) {
        String normalized =
                normalizeUrl(jobUrl);

        int index =
                normalized.lastIndexOf('/');

        return index >= 0
                ? normalized.substring(index + 1)
                : normalized;
    }

    private String findCommitMessage(
            JsonNode response
    ) {
        JsonNode items =
                response.path("changeSet")
                        .path("items");

        if (items.isArray()
                && !items.isEmpty()) {

            return items.get(0)
                    .path("msg")
                    .asText("");
        }

        return "";
    }

    private List<JenkinsBuildSnapshot.Artifact>
    parseArtifacts(
            JsonNode artifactsNode
    ) {
        List<JenkinsBuildSnapshot.Artifact> artifacts =
                new ArrayList<>();

        if (artifactsNode == null
                || !artifactsNode.isArray()) {
            return artifacts;
        }

        for (JsonNode artifact : artifactsNode) {
            artifacts.add(
                    new JenkinsBuildSnapshot.Artifact(
                            artifact.path("fileName")
                                    .asText(""),
                            artifact.path("relativePath")
                                    .asText("")
                    )
            );
        }

        return artifacts;
    }

    private String findFirstGroup(
            Pattern pattern,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return "";
        }

        Matcher matcher =
                pattern.matcher(text);

        return matcher.find()
                ? matcher.group(1).trim()
                : "";
    }

    private JenkinsBuildSnapshot.TestSummary
    parseLastTestSummary(
            String consoleText
    ) {
        if (consoleText == null
                || consoleText.isBlank()) {
            return null;
        }

        Matcher matcher =
                TEST_SUMMARY_PATTERN.matcher(
                        consoleText
                );

        JenkinsBuildSnapshot.TestSummary result =
                null;

        while (matcher.find()) {
            result =
                    new JenkinsBuildSnapshot.TestSummary(
                            Integer.parseInt(
                                    matcher.group(1)
                            ),
                            Integer.parseInt(
                                    matcher.group(2)
                            ),
                            Integer.parseInt(
                                    matcher.group(3)
                            ),
                            Integer.parseInt(
                                    matcher.group(4)
                            )
                    );
        }

        return result;
    }

    private String getText(String url) {
        var cfg =
                properties.getIntegrations()
                        .getJenkins();

        String response =
                webClientBuilder
                        .build()
                        .get()
                        .uri(URI.create(url))
                        .headers(headers ->
                                headers.setBasicAuth(
                                        cfg.getUsername(),
                                        cfg.getToken(),
                                        StandardCharsets.UTF_8
                                )
                        )
                        .retrieve()
                        .onStatus(
                                status ->
                                        status.isError(),
                                clientResponse ->
                                        clientResponse
                                                .bodyToMono(
                                                        String.class
                                                )
                                                .defaultIfEmpty("")
                                                .map(body ->
                                                        new IllegalStateException(
                                                                "Jenkins text request failed. HTTP "
                                                                        + clientResponse
                                                                        .statusCode()
                                                                        + " Response: "
                                                                        + truncate(
                                                                        body,
                                                                        2_000
                                                                )
                                                        )
                                                )
                        )
                        .bodyToMono(String.class)
                        .block(cfg.getTimeout());

        return response == null
                ? ""
                : response;
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
