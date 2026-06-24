package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.BitbucketBranchCheckResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BitbucketHttpClientTest {

    private HttpServer server;
    private ReplayFixProperties properties;
    private BitbucketHttpClient client;
    private List<String> rawPaths;

    @BeforeEach
    void setUp() throws Exception {
        rawPaths = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        properties = new ReplayFixProperties();
        ReplayFixProperties.Bitbucket bitbucket =
                properties.getIntegrations().getBitbucket();
        bitbucket.setEnabled(true);
        bitbucket.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        bitbucket.setTimeout(Duration.ofSeconds(5));
        client = new BitbucketHttpClient(properties, WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void branchExistsMatchesIdForPlainBranch() {
        handler(exchange -> {
            rawPaths.add(exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawPath().endsWith("/branches/test2")) {
                json(exchange, 200, branch("refs/heads/test2", "test2"));
                return;
            }
            json(exchange, 404, "{}");
        });

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "test2");

        assertThat(result.exists()).isTrue();
        assertThat(result.branchName()).isEqualTo("test2");
        assertThat(result.warnings())
                .anyMatch(value -> value.contains("matchedBranchId=refs/heads/test2"));
    }

    @Test
    void branchExistsMatchesDisplayIdForRefsHeadsBranch() {
        handler(exchange -> {
            rawPaths.add(exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawPath()
                    .endsWith("/branches/refs%2Fheads%2Ftest2")) {
                json(exchange, 200, branch("refs/heads/test2", "test2"));
                return;
            }
            json(exchange, 404, "{}");
        });

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "refs/heads/test2");

        assertThat(result.exists()).isTrue();
        assertThat(result.branchName()).isEqualTo("test2");
    }

    @Test
    void branchExistsMatchesSlashContainingIntegrationBranch() {
        handler(exchange -> {
            rawPaths.add(exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawPath()
                    .endsWith("/branches/integration%2Ftest2%2FFIZZMS-10228")) {
                json(exchange, 200, branch(
                        "refs/heads/integration/test2/FIZZMS-10228",
                        "integration/test2/FIZZMS-10228"
                ));
                return;
            }
            json(exchange, 404, "{}");
        });

        BitbucketBranchCheckResult result =
                client.branchExists(
                        "DCE",
                        "backend",
                        "integration/test2/FIZZMS-10228"
                );

        assertThat(result.exists()).isTrue();
        assertThat(rawPaths)
                .contains("/rest/api/1.0/projects/DCE/repos/backend/branches/integration%2Ftest2%2FFIZZMS-10228");
    }

    @Test
    void branchLookupFallsBackToFilterTextWhenDirectLookupMisses() {
        handler(exchange -> {
            rawPaths.add(exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("filterText=bugfix")) {
                json(exchange, 200, branches(branch(
                        "refs/heads/bugfix/FIZZMS-10228",
                        "bugfix/FIZZMS-10228"
                )));
                return;
            }
            json(exchange, 404, "{}");
        });

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "bugfix/FIZZMS-10228");

        assertThat(result.exists()).isTrue();
        assertThat(result.branchName()).isEqualTo("bugfix/FIZZMS-10228");
        assertThat(result.warnings())
                .anyMatch(value -> value.contains("filterText:bugfix/FIZZMS-10228"));
    }

    @Test
    void branchLookupFallsBackToFilterTextWhenDirectLookupErrors() {
        handler(exchange -> {
            rawPaths.add(exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("filterText=test2")) {
                json(exchange, 200, branches(branch(
                        "refs/heads/test2",
                        "test2"
                )));
                return;
            }
            json(exchange, 500, "{\"message\":\"direct lookup failed\"}");
        });

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "test2");

        assertThat(result.exists()).isTrue();
        assertThat(result.branchName()).isEqualTo("test2");
        assertThat(result.warnings())
                .anyMatch(value -> value.contains("filterText:test2"))
                .anyMatch(value -> value.contains("matchedBranchId=refs/heads/test2"))
                .noneMatch("BITBUCKET_BRANCH_LOOKUP_FAILED"::equals);
    }

    @Test
    void branchLookupFallsBackToFilterTextWhenDirectLookupThrows() {
        handler(exchange -> {
            rawPaths.add(exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("filterText=test2")) {
                json(exchange, 200, branches(branch(
                        "refs/heads/test2",
                        "test2"
                )));
                return;
            }
            exchange.close();
        });

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "test2");

        assertThat(result.exists()).isTrue();
        assertThat(result.warnings())
                .anyMatch(value -> value.contains("filterText:test2"))
                .anyMatch(value -> value.contains("httpStatuses=[0, 0, 200]"))
                .anyMatch(value -> value.contains("matchedDisplayId=test2"));
    }

    @Test
    void branchLookupReturnsNotFoundWhenFallbackDoesNotMatch() {
        handler(exchange -> {
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("filterText=missing")) {
                json(exchange, 200, branches(branch(
                        "refs/heads/other",
                        "other"
                )));
                return;
            }
            json(exchange, 404, "{}");
        });

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "missing");

        assertThat(result.exists()).isFalse();
        assertThat(result.warnings())
                .anyMatch(value -> value.contains("filterText:missing"))
                .noneMatch("BITBUCKET_BRANCH_LOOKUP_FAILED"::equals);
    }

    @Test
    void branchLookupFailureReturnsSafeWarning() {
        handler(exchange -> json(exchange, 500, "{\"message\":\"failure\"}"));

        BitbucketBranchCheckResult result =
                client.branchExists("DCE", "backend", "test2");

        assertThat(result.exists()).isFalse();
        assertThat(result.warnings())
                .contains("BITBUCKET_BRANCH_LOOKUP_FAILED");
        assertThat(String.join(",", result.warnings()))
                .doesNotContain("Authorization")
                .doesNotContain("token");
    }

    private void handler(ThrowingHandler handler) {
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                json(exchange, 500, "{}");
            }
        });
    }

    private String branch(String id, String displayId) {
        return "{\"id\":\"" + id + "\",\"displayId\":\"" + displayId + "\"}";
    }

    private String branches(String branch) {
        return "{\"values\":[" + branch + "]}";
    }

    private void json(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
