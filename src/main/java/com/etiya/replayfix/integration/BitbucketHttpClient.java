package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.model.BitbucketConnectionTestResult;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketMergeResult;
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
    public BitbucketBranchCheckResult branchExists(
            String projectKey,
            String repositorySlug,
            String branchName
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        if (!cfg.isEnabled()) {
            return new BitbucketBranchCheckResult(
                    false,
                    branchName,
                    List.of("Bitbucket integration is disabled")
            );
        }
        try {
            JsonNode response = webClientBuilder
                    .baseUrl(cfg.getBaseUrl().replaceAll("/+$", ""))
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/rest/api/1.0/projects/{project}/repos/{repo}/branches")
                            .queryParam("filterText", branchName)
                            .queryParam("limit", 100)
                            .build(projectKey, repositorySlug))
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body -> new IllegalStateException(
                                            "Bitbucket branch read failed. HTTP "
                                                    + clientResponse.statusCode()
                                                    + " Response: "
                                                    + truncate(body, 2_000)
                                    ))
                    )
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());
            boolean exists = false;
            if (response != null) {
                for (JsonNode branch : response.path("values")) {
                    String id = branch.path("id").asText("");
                    String displayId = branch.path("displayId").asText("");
                    if (branchName.equals(displayId)
                            || ("refs/heads/" + branchName).equals(id)) {
                        exists = true;
                        break;
                    }
                }
            }
            return new BitbucketBranchCheckResult(exists, branchName, List.of());
        } catch (Exception exception) {
            return new BitbucketBranchCheckResult(
                    false,
                    branchName,
                    List.of(rootCauseMessage(exception))
            );
        }
    }

    @Override
    public BitbucketBranchCreateResult createBranch(
            String projectKey,
            String repositorySlug,
            String branchName,
            String startPoint
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        if (!cfg.isEnabled()) {
            return new BitbucketBranchCreateResult(
                    false,
                    false,
                    branchName,
                    List.of("Bitbucket integration is disabled")
            );
        }
        try {
            Map<String, Object> body = Map.of(
                    "name", branchName,
                    "startPoint", startPoint
            );
            webClientBuilder
                    .baseUrl(cfg.getBaseUrl().replaceAll("/+$", ""))
                    .build()
                    .post()
                    .uri("/rest/branch-utils/1.0/projects/{project}/repos/{repo}/branches",
                            projectKey, repositorySlug)
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(errorBody -> new IllegalStateException(
                                            "Bitbucket branch create failed. HTTP "
                                                    + clientResponse.statusCode()
                                                    + " Response: "
                                                    + truncate(errorBody, 2_000)
                                    ))
                    )
                    .toBodilessEntity()
                    .block(cfg.getTimeout());
            return new BitbucketBranchCreateResult(
                    true,
                    false,
                    branchName,
                    List.of()
            );
        } catch (Exception exception) {
            String message = rootCauseMessage(exception);
            boolean alreadyExists = message.toLowerCase().contains("already");
            return new BitbucketBranchCreateResult(
                    false,
                    alreadyExists,
                    branchName,
                    List.of(message)
            );
        }
    }

    @Override
    public BitbucketMergeResult mergeBranches(
            String projectKey,
            String repositorySlug,
            String sourceBranch,
            String targetBranch
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        if (!cfg.isEnabled()) {
            return new BitbucketMergeResult(
                    false,
                    false,
                    false,
                    List.of("Bitbucket integration is disabled")
            );
        }
        try {
            Map<String, Object> body = Map.of(
                    "fromRef", Map.of("id", "refs/heads/" + sourceBranch),
                    "toRef", Map.of("id", "refs/heads/" + targetBranch),
                    "message", "ReplayFix guarded branch merge"
            );
            webClientBuilder
                    .baseUrl(cfg.getBaseUrl().replaceAll("/+$", ""))
                    .build()
                    .post()
                    .uri("/rest/api/1.0/projects/{project}/repos/{repo}/merge",
                            projectKey, repositorySlug)
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(errorBody -> new IllegalStateException(
                                            "Bitbucket branch merge failed. HTTP "
                                                    + clientResponse.statusCode()
                                                    + " Response: "
                                                    + truncate(errorBody, 2_000)
                                    ))
                    )
                    .toBodilessEntity()
                    .block(cfg.getTimeout());
            return new BitbucketMergeResult(true, true, false, List.of());
        } catch (Exception exception) {
            String message = rootCauseMessage(exception);
            boolean conflict = message.toLowerCase().contains("conflict");
            return new BitbucketMergeResult(
                    true,
                    false,
                    conflict,
                    List.of(message)
            );
        }
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
        return createPullRequest(
                cfg.getProjectKey(),
                target.getRepository(),
                sourceBranch,
                destinationBranch,
                title,
                description,
                reviewers
        );
    }

    @Override
    public PullRequestResult createPullRequest(
            String projectKey,
            String repositorySlug,
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
                            cfg.getWorkspace(), repositorySlug)
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
                "slug", repositorySlug,
                "project", Map.of("key", projectKey)
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
                        projectKey, repositorySlug)
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
