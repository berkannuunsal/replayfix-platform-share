package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class BitbucketSourceReadClient {

    public static final String CREDENTIALS_NOT_CONFIGURED =
            "BITBUCKET_CREDENTIALS_NOT_CONFIGURED";
    public static final String READ_NOT_AUTHORIZED =
            "BITBUCKET_READ_NOT_AUTHORIZED";
    public static final String FILE_NOT_FOUND =
            "BITBUCKET_FILE_NOT_FOUND";
    public static final String READ_FAILED =
            "BITBUCKET_READ_FAILED";

    private final WebClient.Builder webClientBuilder;

    public BitbucketSourceReadClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public SourceFileFetchResult fetchRawFile(
            ReplayFixProperties.SourceCandidateBitbucket bitbucket,
            ReplayFixProperties.SourceCandidateRepository repository,
            String filePath,
            String branch
    ) {
        String username = env(bitbucket.getUsernameEnv());
        String token = env(firstNonBlank(
                bitbucket.getTokenEnv(),
                bitbucket.getAccessKeyEnv()
        ));
        if (username.isBlank() || token.isBlank()) {
            return SourceFileFetchResult.failure(
                    CREDENTIALS_NOT_CONFIGURED,
                    repository,
                    branch,
                    filePath,
                    List.of()
            );
        }

        try {
            String body = webClientBuilder
                    .baseUrl(trimTrailingSlash(bitbucket.getBaseUrl()))
                    .build()
                    .get()
                    .uri(rawPath(repository, filePath, branch))
                    .headers(headers -> headers.setBasicAuth(
                            username,
                            token
                    ))
                    .exchangeToMono(response -> {
                        HttpStatus status = HttpStatus.resolve(
                                response.statusCode().value()
                        );
                        if (status == HttpStatus.UNAUTHORIZED
                                || status == HttpStatus.FORBIDDEN) {
                            return response.releaseBody()
                                    .thenReturn(READ_NOT_AUTHORIZED);
                        }
                        if (status == HttpStatus.NOT_FOUND) {
                            return response.releaseBody()
                                    .thenReturn(FILE_NOT_FOUND);
                        }
                        if (response.statusCode().isError()) {
                            return response.releaseBody()
                                    .thenReturn(READ_FAILED + ":HTTP_"
                                            + response.statusCode().value());
                        }
                        return response.bodyToMono(String.class);
                    })
                    .block(Duration.ofSeconds(Math.max(
                            1,
                            bitbucket.getRequestTimeoutSeconds()
                    )));

            if (body == null) {
                return SourceFileFetchResult.failure(
                        READ_FAILED,
                        repository,
                        branch,
                        filePath,
                        List.of("BITBUCKET_EMPTY_RESPONSE")
                );
            }
            if (body.equals(READ_NOT_AUTHORIZED)
                    || body.equals(FILE_NOT_FOUND)
                    || body.startsWith(READ_FAILED)) {
                return SourceFileFetchResult.failure(
                        body,
                        repository,
                        branch,
                        filePath,
                        List.of()
                );
            }
            return new SourceFileFetchResult(
                    true,
                    "OK",
                    body,
                    repository.getLogicalName(),
                    repository.getProjectKey(),
                    repository.getRepositorySlug(),
                    branch,
                    filePath,
                    List.of()
            );
        } catch (Exception exception) {
            return SourceFileFetchResult.failure(
                    READ_FAILED,
                    repository,
                    branch,
                    filePath,
                    List.of("BITBUCKET_READ_EXCEPTION")
            );
        }
    }

    public String buildRawFileUrl(
            ReplayFixProperties.SourceCandidateBitbucket bitbucket,
            ReplayFixProperties.SourceCandidateRepository repository,
            String filePath,
            String branch
    ) {
        return trimTrailingSlash(bitbucket.getBaseUrl())
                + rawPath(repository, filePath, branch);
    }

    public String rawPath(
            ReplayFixProperties.SourceCandidateRepository repository,
            String filePath,
            String branch
    ) {
        String encodedPath = encodePath(filePath);
        String configuredTemplate = repository.getRawPathTemplate();
        if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            return configuredTemplate
                    .replace("{path}", encodedPath)
                    .replace("{branch}", branch);
        }
        return "/rest/api/1.0/projects/"
                + encode(repository.getProjectKey())
                + "/repos/"
                + encode(repository.getRepositorySlug())
                + "/raw/"
                + encodedPath
                + "?at=refs/heads/"
                + encode(branch);
    }

    private String encodePath(String filePath) {
        String normalized = filePath == null ? "" : filePath.replace("\\", "/");
        String[] segments = normalized.split("/");
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(encode(segment));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String env(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    public record SourceFileFetchResult(
            boolean success,
            String status,
            String content,
            String repositoryLogicalName,
            String projectKey,
            String repositorySlug,
            String branch,
            String filePath,
            List<String> warnings
    ) {
        public SourceFileFetchResult {
            content = content == null ? "" : content;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static SourceFileFetchResult failure(
                String status,
                ReplayFixProperties.SourceCandidateRepository repository,
                String branch,
                String filePath,
                List<String> warnings
        ) {
            return new SourceFileFetchResult(
                    false,
                    status,
                    "",
                    repository == null ? "" : repository.getLogicalName(),
                    repository == null ? "" : repository.getProjectKey(),
                    repository == null ? "" : repository.getRepositorySlug(),
                    branch,
                    filePath,
                    warnings
            );
        }
    }
}
