package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.model.BitbucketConnectionTestResult;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BitbucketHttpClient implements BitbucketClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public BitbucketHttpClient(ReplayFixProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public BitbucketConnectionTestResult testConnection() {
        var cfg = properties.getIntegrations().getBitbucket();

        if (!cfg.isEnabled()) {
            return new BitbucketConnectionTestResult(
                    false,
                    cfg.getProvider(),
                    cfg.getProjectKey(),
                    0,
                    List.of(),
                    "Bitbucket integration is disabled."
            );
        }

        try {
            List<BitbucketRepositoryInfo> repositories =
                    listRepositories();

            return new BitbucketConnectionTestResult(
                    true,
                    cfg.getProvider(),
                    cfg.getProjectKey(),
                    repositories.size(),
                    repositories,
                    "Bitbucket connection successful."
            );

        } catch (Exception exception) {
            return new BitbucketConnectionTestResult(
                    false,
                    cfg.getProvider(),
                    cfg.getProjectKey(),
                    0,
                    List.of(),
                    rootCauseMessage(exception)
            );
        }
    }

    @Override
    public List<BitbucketRepositoryInfo> listRepositories() {
        var cfg = properties.getIntegrations().getBitbucket();

        if (!cfg.isEnabled()) {
            return List.of();
        }

        if (!"DATA_CENTER".equalsIgnoreCase(
                cfg.getProvider()
        )) {
            throw new IllegalStateException(
                    "Repository discovery currently supports "
                            + "Bitbucket Data Center only."
            );
        }

        JsonNode response = webClientBuilder
                .baseUrl(
                        cfg.getBaseUrl()
                                .replaceAll("/+$", "")
                )
                .build()
                .get()
                .uri(uriBuilder ->
                        uriBuilder
                                .path(
                                        "/rest/api/1.0/projects/"
                                                + "{projectKey}/repos"
                                )
                                .queryParam("limit", 1000)
                                .build(cfg.getProjectKey())
                )
                .headers(headers ->
                        headers.addAll(
                                HttpSupport.headers(cfg)
                        )
                )
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse ->
                                clientResponse
                                        .bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .map(body ->
                                                new IllegalStateException(
                                                        "Bitbucket request failed. HTTP "
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
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        if (response == null) {
            throw new IllegalStateException(
                    "Bitbucket returned an empty response."
            );
        }

        List<BitbucketRepositoryInfo> repositories =
                new ArrayList<>();

        for (JsonNode repository :
                response.path("values")) {

            String slug =
                    repository.path("slug").asText("");

            String projectKey =
                    repository.path("project")
                            .path("key")
                            .asText(cfg.getProjectKey());

            String cloneUrl =
                    findCloneUrl(repository);

            repositories.add(
                    new BitbucketRepositoryInfo(
                            projectKey,
                            slug,
                            repository.path("name")
                                    .asText(slug),
                            repository.path("defaultBranch")
                                    .asText(""),
                            repository.path("state")
                                    .asText(""),
                            repository.path("archived")
                                    .asBoolean(false),
                            cloneUrl
                    )
            );
        }

        return repositories;
    }

    @Override
    public PullRequestResult createPullRequest(
            Target target,
            String sourceBranch,
            String destinationBranch,
            String title,
            String description,
            List<String> reviewers
    ) {
        var cfg = properties.getIntegrations().getBitbucket();

        if (!cfg.isEnabled()) {
            return new PullRequestResult("dry-run", "dry-run://bitbucket/pr/1", title);
        }

        if ("CLOUD".equalsIgnoreCase(cfg.getProvider())) {
            List<Map<String, Object>> reviewerPayload = new ArrayList<>();
            reviewers.forEach(reviewer -> reviewerPayload.add(Map.of("uuid", reviewer)));

            Map<String, Object> body = Map.of(
                    "title", title,
                    "description", description,
                    "source", Map.of("branch", Map.of("name", sourceBranch)),
                    "destination", Map.of("branch", Map.of("name", destinationBranch)),
                    "reviewers", reviewerPayload
            );

            JsonNode node = webClientBuilder.baseUrl(cfg.getBaseUrl()).build().post()
                    .uri("/2.0/repositories/{workspace}/{repo}/pullrequests",
                            cfg.getWorkspace(), target.getRepository())
                    .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            if (node == null) throw new IllegalStateException("Bitbucket Cloud returned an empty response");
            return new PullRequestResult(
                    node.path("id").asText(),
                    node.path("links").path("html").path("href").asText(),
                    title
            );
        }

        List<Map<String, Object>> reviewerPayload = new ArrayList<>();
        reviewers.forEach(reviewer ->
                reviewerPayload.add(Map.of("user", Map.of("name", reviewer))));

        Map<String, Object> repository = Map.of(
                "slug", target.getRepository(),
                "project", Map.of("key", cfg.getProjectKey())
        );

        Map<String, Object> body = Map.of(
                "title", title,
                "description", description,
                "fromRef", Map.of(
                        "id", "refs/heads/" + sourceBranch,
                        "repository", repository
                ),
                "toRef", Map.of(
                        "id", "refs/heads/" + destinationBranch,
                        "repository", repository
                ),
                "reviewers", reviewerPayload
        );

        JsonNode node = webClientBuilder.baseUrl(cfg.getBaseUrl()).build().post()
                .uri("/rest/api/1.0/projects/{project}/repos/{repo}/pull-requests",
                        cfg.getProjectKey(), target.getRepository())
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(cfg.getTimeout());

        if (node == null) throw new IllegalStateException("Bitbucket Data Center returned an empty response");
        return new PullRequestResult(
                node.path("id").asText(),
                node.path("links").path("self").path(0).path("href").asText(),
                title
        );
    }

    private String findCloneUrl(JsonNode repository) {
        JsonNode cloneLinks =
                repository.path("links")
                        .path("clone");

        if (cloneLinks.isArray()) {
            for (JsonNode link : cloneLinks) {
                if ("http".equalsIgnoreCase(
                        link.path("name").asText("")
                )) {
                    return link.path("href").asText("");
                }
            }
        }

        return "";
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
}
