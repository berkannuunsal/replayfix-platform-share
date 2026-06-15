package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.TempoTraceResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

@Component
public class TempoHttpClient implements TempoClient {

    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public TempoHttpClient(
            ReplayFixProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public TempoTraceResult getTrace(
            String traceId,
            Instant start,
            Instant end
    ) {
        var cfg = properties.getIntegrations().getTempo();

        if (!cfg.isEnabled()) {
            return new TempoTraceResult(
                    traceId,
                    false,
                    "Tempo integration is disabled.",
                    "{}"
            );
        }

        if (traceId == null || traceId.isBlank()) {
            return new TempoTraceResult(
                    "",
                    false,
                    "Trace ID is blank.",
                    "{}"
            );
        }

        try {
            String baseUrl = cfg.getBaseUrl()
                    .replaceAll("/+$", "");

            String apiPath = cfg.getApiPath() == null
                    ? ""
                    : cfg.getApiPath().trim();

            if (!apiPath.isBlank()
                    && !apiPath.startsWith("/")) {
                apiPath = "/" + apiPath;
            }

            apiPath = apiPath.replaceAll("/+$", "");

            String endpoint = baseUrl
                    + apiPath
                    + "/api/traces/"
                    + traceId;

            UriComponentsBuilder builder =
                    UriComponentsBuilder
                            .fromHttpUrl(endpoint);

            if (start != null && end != null) {
                builder.queryParam(
                        "start",
                        start.getEpochSecond()
                );
                builder.queryParam(
                        "end",
                        end.getEpochSecond()
                );
            }

            URI requestUri = builder
                    .build()
                    .encode()
                    .toUri();

            String raw = webClientBuilder
                    .build()
                    .get()
                    .uri(requestUri)
                    .headers(headers ->
                            headers.addAll(
                                    HttpSupport.headers(cfg)
                            )
                    )
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            response -> response
                                    .bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body ->
                                            new IllegalStateException(
                                                    "Tempo request failed. HTTP "
                                                            + response.statusCode()
                                                            + " Response: "
                                                            + body
                                            )
                                    )
                    )
                    .bodyToMono(String.class)
                    .block(cfg.getTimeout());

            return new TempoTraceResult(
                    traceId,
                    raw != null && !raw.isBlank(),
                    null,
                    raw == null ? "{}" : raw
            );

        } catch (Exception exception) {
            return new TempoTraceResult(
                    traceId,
                    false,
                    rootCauseMessage(exception),
                    "{}"
            );
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getClass().getSimpleName()
                + ": "
                + root.getMessage();
    }
}
