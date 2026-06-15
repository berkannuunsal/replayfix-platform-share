package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
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
}
