package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.LokiLogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;

@Component
public class LokiHttpClient implements LokiClient {
    private static final Logger log =
            LoggerFactory.getLogger(LokiHttpClient.class);

    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public LokiHttpClient(ReplayFixProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public List<LokiLogEntry> queryRange(
        String logQl,
        Instant start,
        Instant end,
        int limit
    ) {
        var cfg = properties.getIntegrations().getLoki();

        if (!cfg.isEnabled()) {
            return List.of(
                new LokiLogEntry(
                    Instant.now(),
                    "{\"app\":\"dry-run\"}",
                    "ERROR orderId=DEMO-ORDER-1 "
                        + "traceId=demo-trace "
                        + "IllegalStateException"
                )
            );
        }

        String baseUrl = cfg.getBaseUrl()
                .replaceAll("/+$", "");

        String apiPath = cfg.getApiPath() == null
                ? ""
                : cfg.getApiPath().trim();

        if (!apiPath.isBlank() && !apiPath.startsWith("/")) {
            apiPath = "/" + apiPath;
        }

        apiPath = apiPath.replaceAll("/+$", "");

        String endpoint = baseUrl
                + apiPath
                + "/loki/api/v1/query_range";

        URI requestUri = UriComponentsBuilder
                .fromHttpUrl(endpoint)
                .queryParam("query", logQl)
                .queryParam(
                        "start",
                        start.getEpochSecond() * 1_000_000_000L
                )
                .queryParam(
                        "end",
                        end.getEpochSecond() * 1_000_000_000L
                )
                .queryParam("limit", limit)
                .queryParam("direction", "backward")
                .build()
                .encode()
                .toUri();

        log.debug(
                "ReplayLab Loki query_range request. endpoint={}, uri={}",
                endpoint,
                requestUri
        );

        JsonNode node = webClientBuilder
            .build()
            .get()
            .uri(requestUri)
            .headers(headers ->
                headers.addAll(HttpSupport.headers(cfg))
            )
            .retrieve()
            .onStatus(
                status -> status.isError(),
                response -> response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> new IllegalStateException(
                        "Loki request failed. HTTP "
                            + response.statusCode()
                            + " Response: "
                            + body
                    ))
            )
            .bodyToMono(JsonNode.class)
            .block(cfg.getTimeout());

        List<LokiLogEntry> logs = new ArrayList<>();

        if (node == null) {
            return logs;
        }

        for (JsonNode stream :
            node.path("data").path("result")) {

            String labels = stream.path("stream").toString();

            for (JsonNode value : stream.path("values")) {
                long nanos = Long.parseLong(
                    value.get(0).asText()
                );

                Instant timestamp = Instant.ofEpochSecond(
                    nanos / 1_000_000_000L,
                    nanos % 1_000_000_000L
                );

                logs.add(
                    new LokiLogEntry(
                        timestamp,
                        labels,
                        value.get(1).asText()
                    )
                );
            }
        }

        return logs;
    }
}
