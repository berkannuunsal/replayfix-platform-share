package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.model.BitbucketConnectionTestResult;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCreateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketFileUpdateResult;
import com.etiya.replayfix.model.IntegrationModels.BitbucketMergeResult;
import com.etiya.replayfix.model.IntegrationModels.PullRequestResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String normalized = normalizeBranchName(branchName);
        BranchLookupDiagnostics diagnostics =
                new BranchLookupDiagnostics(branchName, normalized);
        for (String candidate : directCandidates(branchName)) {
            BranchLookupAttempt attempt = directBranch(
                    projectKey,
                    repositorySlug,
                    candidate,
                    diagnostics
            );
            if (attempt.failed()) {
                continue;
            }
            if (attempt.branch() != null
                    && branchMatches(attempt.branch(), normalized)) {
                diagnostics.match(attempt.branch());
                return new BitbucketBranchCheckResult(
                        true,
                        normalized,
                        List.of(diagnostics.warning())
                );
            }
        }
        BranchLookupAttempt filtered = filteredBranches(
                projectKey,
                repositorySlug,
                normalized,
                diagnostics
        );
        if (filtered.failed()) {
            return new BitbucketBranchCheckResult(
                    false,
                    branchName,
                    List.of(
                            "BITBUCKET_BRANCH_LOOKUP_FAILED",
                            diagnostics.warning()
                    )
            );
        }
        if (filtered.branches() != null) {
            for (JsonNode branch : filtered.branches()) {
                if (branchMatches(branch, normalized)) {
                    diagnostics.match(branch);
                    return new BitbucketBranchCheckResult(
                            true,
                            normalized,
                            List.of(diagnostics.warning())
                    );
                }
            }
        }
        return new BitbucketBranchCheckResult(
                false,
                normalized,
                List.of(diagnostics.warning())
        );
    }

    BranchLookupAttempt directBranch(
            String projectKey,
            String repositorySlug,
            String branchName,
            BranchLookupDiagnostics diagnostics
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        diagnostics.strategy("direct:" + branchName);
        try {
            return webClientBuilder
                    .baseUrl(cfg.getBaseUrl().replaceAll("/+$", ""))
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/rest/api/1.0/projects/{project}/repos/{repo}/branches")
                            .pathSegment(branchName)
                            .build(projectKey, repositorySlug))
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                            .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                            .map(body -> {
                                HttpStatusCode status = response.statusCode();
                                diagnostics.status(status.value());
                                if (status.value() == 404) {
                                    return new BranchLookupAttempt(false, null, null);
                                }
                                if (status.isError()) {
                                    return new BranchLookupAttempt(true, null, null);
                                }
                                return new BranchLookupAttempt(false, body, null);
                            }))
                    .block(cfg.getTimeout());
        } catch (Exception exception) {
            diagnostics.status(0);
            return new BranchLookupAttempt(true, null, null);
        }
    }

    BranchLookupAttempt filteredBranches(
            String projectKey,
            String repositorySlug,
            String branchName,
            BranchLookupDiagnostics diagnostics
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        diagnostics.strategy("filterText:" + branchName);
        try {
            return webClientBuilder
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
                    .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                            .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                            .map(body -> {
                                HttpStatusCode status = response.statusCode();
                                diagnostics.status(status.value());
                                if (status.isError()) {
                                    return new BranchLookupAttempt(true, null, null);
                                }
                                List<JsonNode> branches = new ArrayList<>();
                                for (JsonNode branch : body.path("values")) {
                                    branches.add(branch);
                                }
                                return new BranchLookupAttempt(false, null, branches);
                            }))
                    .block(cfg.getTimeout());
        } catch (Exception exception) {
            diagnostics.status(0);
            return new BranchLookupAttempt(true, null, null);
        }
    }

    boolean branchMatches(JsonNode branch, String normalizedRequested) {
        String id = normalizeBranchName(branch.path("id").asText(""));
        String displayId = normalizeBranchName(branch.path("displayId").asText(""));
        return normalizedRequested.equals(id)
                || normalizedRequested.equals(displayId);
    }

    String normalizeBranchName(String branchName) {
        if (branchName == null) {
            return "";
        }
        String value = branchName.trim();
        return value.startsWith("refs/heads/")
                ? value.substring("refs/heads/".length())
                : value;
    }

    private List<String> directCandidates(String branchName) {
        String normalized = normalizeBranchName(branchName);
        Set<String> values = new LinkedHashSet<>();
        if (branchName != null && !branchName.isBlank()) {
            values.add(branchName.trim());
        }
        if (!normalized.isBlank()) {
            values.add("refs/heads/" + normalized);
        }
        return List.copyOf(values);
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
    public BitbucketFileUpdateResult updateFile(
            String projectKey,
            String repositorySlug,
            String branchName,
            String filePath,
            String content,
            String commitMessage
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        if (!cfg.isEnabled()) {
            return new BitbucketFileUpdateResult(
                    false,
                    branchName,
                    filePath,
                    "",
                    List.of("Bitbucket integration is disabled")
            );
        }
        try {
            String latestCommit = latestCommitForBranch(projectKey, repositorySlug, branchName);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("branch", normalizeBranchName(branchName));
            form.add("content", content == null ? "" : content);
            form.add("message", commitMessage == null ? "" : commitMessage);
            if (!latestCommit.isBlank()) {
                form.add("sourceCommitId", latestCommit);
            }

            JsonNode node = webClientBuilder
                    .baseUrl(cfg.getBaseUrl().replaceAll("/+$", ""))
                    .build()
                    .put()
                    .uri(uriBuilder -> uriBuilder
                            .path("/rest/api/1.0/projects/{project}/repos/{repo}/browse/")
                            .path(filePath)
                            .build(projectKey, repositorySlug))
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(errorBody -> new IllegalStateException(
                                            "Bitbucket file update failed. HTTP "
                                                    + clientResponse.statusCode()
                                                    + " Response: "
                                                    + truncate(errorBody, 2_000)
                                    ))
                    )
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());

            String commitSha = "";
            if (node != null) {
                commitSha = firstNonBlank(
                        node.path("commit").path("id").asText(""),
                        node.path("latestCommit").asText(""),
                        node.path("id").asText("")
                );
            }
            return new BitbucketFileUpdateResult(
                    true,
                    normalizeBranchName(branchName),
                    filePath,
                    commitSha,
                    List.of()
            );
        } catch (Exception exception) {
            return new BitbucketFileUpdateResult(
                    false,
                    branchName,
                    filePath,
                    "",
                    List.of(rootCauseMessage(exception))
            );
        }
    }

    @Override
    public PullRequestResult findOpenPullRequest(
            String projectKey,
            String repositorySlug,
            String sourceBranch,
            String destinationBranch
    ) {
        var cfg = properties.getIntegrations().getBitbucket();
        if (!cfg.isEnabled()) {
            return new PullRequestResult("", "", "");
        }
        try {
            JsonNode node = webClientBuilder
                    .baseUrl(cfg.getBaseUrl().replaceAll("/+$", ""))
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/rest/api/1.0/projects/{project}/repos/{repo}/pull-requests")
                            .queryParam("state", "OPEN")
                            .queryParam("limit", 100)
                            .build(projectKey, repositorySlug))
                    .headers(headers -> headers.addAll(HttpSupport.headers(cfg)))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(cfg.getTimeout());
            if (node == null) {
                return new PullRequestResult("", "", "");
            }
            String normalizedSource = normalizeBranchName(sourceBranch);
            String normalizedTarget = normalizeBranchName(destinationBranch);
            for (JsonNode pullRequest : node.path("values")) {
                String fromId = normalizeBranchName(pullRequest.path("fromRef").path("id").asText(""));
                String fromDisplay = normalizeBranchName(pullRequest.path("fromRef").path("displayId").asText(""));
                String toId = normalizeBranchName(pullRequest.path("toRef").path("id").asText(""));
                String toDisplay = normalizeBranchName(pullRequest.path("toRef").path("displayId").asText(""));
                boolean sourceMatches = normalizedSource.equals(fromId) || normalizedSource.equals(fromDisplay);
                boolean targetMatches = normalizedTarget.equals(toId) || normalizedTarget.equals(toDisplay);
                if (sourceMatches && targetMatches) {
                    return new PullRequestResult(
                            pullRequest.path("id").asText(""),
                            pullRequest.path("links").path("self").path(0).path("href").asText(""),
                            pullRequest.path("title").asText("")
                    );
                }
            }
            return new PullRequestResult("", "", "");
        } catch (Exception exception) {
            return new PullRequestResult("", "", "");
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

    private String latestCommitForBranch(
            String projectKey,
            String repositorySlug,
            String branchName
    ) {
        BranchLookupDiagnostics diagnostics =
                new BranchLookupDiagnostics(branchName, normalizeBranchName(branchName));
        BranchLookupAttempt filtered = filteredBranches(
                projectKey,
                repositorySlug,
                normalizeBranchName(branchName),
                diagnostics
        );
        if (filtered.failed() || filtered.branches() == null) {
            return "";
        }
        String normalized = normalizeBranchName(branchName);
        for (JsonNode branch : filtered.branches()) {
            if (branchMatches(branch, normalized)) {
                return firstNonBlank(
                        branch.path("latestCommit").asText(""),
                        branch.path("latestChangeset").asText("")
                );
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    record BranchLookupAttempt(
            boolean failed,
            JsonNode branch,
            List<JsonNode> branches
    ) {
    }

    static final class BranchLookupDiagnostics {
        private final String requested;
        private final String normalized;
        private final List<String> strategies = new ArrayList<>();
        private final List<Integer> statuses = new ArrayList<>();
        private String matchedBranchId = "";
        private String matchedDisplayId = "";

        BranchLookupDiagnostics(String requested, String normalized) {
            this.requested = requested == null ? "" : requested;
            this.normalized = normalized == null ? "" : normalized;
        }

        void strategy(String value) {
            if (value != null && !value.isBlank()) {
                strategies.add(value);
            }
        }

        void status(int value) {
            statuses.add(value);
        }

        void match(JsonNode branch) {
            matchedBranchId = branch.path("id").asText("");
            matchedDisplayId = branch.path("displayId").asText("");
        }

        String warning() {
            return "BRANCH_LOOKUP_DIAGNOSTICS"
                    + "|requested=" + requested
                    + "|normalized=" + normalized
                    + "|strategies=" + String.join(",", strategies)
                    + "|httpStatuses=" + statuses
                    + "|matchedBranchId=" + matchedBranchId
                    + "|matchedDisplayId=" + matchedDisplayId;
        }
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
