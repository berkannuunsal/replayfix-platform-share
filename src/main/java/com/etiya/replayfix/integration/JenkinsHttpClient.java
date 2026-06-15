package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.BuildResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
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

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
