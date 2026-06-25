package com.etiya.replaylab.integration;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.model.TempoConnectivityResult;
import com.etiya.replaylab.model.TempoRawTrace;
import com.etiya.replaylab.model.TempoTraceResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class TempoHttpClient implements TempoClient {

    private final ReplayLabProperties properties;
    private final WebClient.Builder webClientBuilder;

    public TempoHttpClient(
            ReplayLabProperties properties,
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

    @Override
    public TempoRawTrace fetchTrace(String traceId) {
        var cfg = properties.getIntegrations().getTempo();

        if (!cfg.isEnabled()) {
            return new TempoRawTrace(
                    traceId,
                    0,
                    null,
                    null,
                    false,
                    List.of("Tempo integration is disabled")
            );
        }

        if (!isValidTraceId(traceId)) {
            return new TempoRawTrace(
                    traceId,
                    0,
                    null,
                    null,
                    false,
                    List.of("Invalid trace ID format")
            );
        }

        try {
            String endpoint = buildTraceEndpoint(traceId);

            WebClient.ResponseSpec response = webClientBuilder
                    .build()
                    .get()
                    .uri(endpoint)
                    .headers(headers ->
                            headers.addAll(
                                    HttpSupport.headers(cfg)
                            )
                    )
                    .retrieve();

            int[] statusCode = {200};
            String[] contentType = {null};

            String raw = response
                    .toEntity(String.class)
                    .map(entity -> {
                        statusCode[0] = entity.getStatusCode().value();
                        contentType[0] = entity.getHeaders().getContentType() != null
                                ? entity.getHeaders().getContentType().toString()
                                : null;
                        return entity.getBody();
                    })
                    .block(cfg.getTimeout());

            List<String> warnings = new ArrayList<>();

            if (isHtmlResponse(contentType[0], raw)) {
                return new TempoRawTrace(
                        traceId,
                        statusCode[0],
                        contentType[0],
                        null,
                        false,
                        List.of("Tempo endpoint returned HTML instead of trace JSON. " +
                                "Check base URL, datasource UID, proxy path and authentication.")
                );
            }

            boolean found = raw != null && !raw.isBlank() && statusCode[0] == 200;

            if (!found && statusCode[0] == 404) {
                warnings.add("Trace not found in Tempo");
            }

            return new TempoRawTrace(
                    traceId,
                    statusCode[0],
                    contentType[0],
                    raw,
                    found,
                    warnings
            );

        } catch (Exception exception) {
            return new TempoRawTrace(
                    traceId,
                    0,
                    null,
                    null,
                    false,
                    List.of(rootCauseMessage(exception))
            );
        }
    }

    @Override
    public TempoConnectivityResult connectivity() {
        var cfg = properties.getIntegrations().getTempo();

        List<String> warnings = new ArrayList<>();

        boolean baseUrlConfigured = cfg.getBaseUrl() != null
                && !cfg.getBaseUrl().isBlank();

        boolean datasourceUidConfigured = cfg.getDatasourceUid() != null
                && !cfg.getDatasourceUid().isBlank();

        boolean tokenConfigured = cfg.getToken() != null
                && !cfg.getToken().isBlank();

        String accessMode = cfg.getAccessMode() != null
                ? cfg.getAccessMode()
                : "UNKNOWN";

        if (!cfg.isEnabled()) {
            warnings.add("Tempo integration is disabled");
            return new TempoConnectivityResult(
                    false,
                    accessMode,
                    baseUrlConfigured,
                    datasourceUidConfigured,
                    tokenConfigured,
                    null,
                    null,
                    "UNKNOWN",
                    warnings
            );
        }

        if (!baseUrlConfigured) {
            warnings.add("Base URL is not configured");
        }

        if ("GRAFANA_PROXY".equals(accessMode) && !datasourceUidConfigured) {
            warnings.add("Grafana proxy mode requires datasource UID");
        }

        try {
            String testTraceId = "00000000000000000000000000000000";
            String endpoint = buildTraceEndpoint(testTraceId);

            int[] statusCode = {0};
            String[] contentType = {null};

            try {
                webClientBuilder
                        .build()
                        .get()
                        .uri(endpoint)
                        .headers(headers ->
                                headers.addAll(
                                        HttpSupport.headers(cfg)
                                )
                        )
                        .retrieve()
                        .toEntity(String.class)
                        .map(entity -> {
                            statusCode[0] = entity.getStatusCode().value();
                            contentType[0] = entity.getHeaders().getContentType() != null
                                    ? entity.getHeaders().getContentType().toString()
                                    : null;
                            return entity.getBody();
                        })
                        .block(cfg.getTimeout());

            } catch (Exception ignored) {
            }

            boolean isHtml = isHtmlResponse(contentType[0], null);

            if (isHtml) {
                warnings.add("Endpoint returned HTML, likely Grafana login page");
            }

            boolean success = !isHtml
                    && statusCode[0] > 0
                    && (statusCode[0] == 404 || statusCode[0] == 200);

            String endpointCategory = "GRAFANA_PROXY".equals(accessMode)
                    ? "GRAFANA_PROXY_TRACE_API"
                    : "DIRECT_TEMPO_TRACE_API";

            return new TempoConnectivityResult(
                    success,
                    accessMode,
                    baseUrlConfigured,
                    datasourceUidConfigured,
                    tokenConfigured,
                    statusCode[0],
                    contentType[0],
                    endpointCategory,
                    warnings
            );

        } catch (Exception exception) {
            warnings.add("Connection failed: " + rootCauseMessage(exception));

            return new TempoConnectivityResult(
                    false,
                    accessMode,
                    baseUrlConfigured,
                    datasourceUidConfigured,
                    tokenConfigured,
                    null,
                    null,
                    "UNKNOWN",
                    warnings
            );
        }
    }

    private String buildTraceEndpoint(String traceId) {
        var cfg = properties.getIntegrations().getTempo();

        String baseUrl = cfg.getBaseUrl()
                .replaceAll("/+$", "");

        if ("GRAFANA_PROXY".equals(cfg.getAccessMode())) {
            String datasourceUid = cfg.getDatasourceUid();
            return baseUrl + "/api/datasources/proxy/uid/"
                    + datasourceUid + "/api/traces/" + traceId;
        } else {
            return baseUrl + "/api/traces/" + traceId;
        }
    }

    private boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }

        String normalized = traceId.toLowerCase().replaceAll("[\\s\\-_]", "");
        int length = normalized.length();

        return (length == 16 || length == 32) && normalized.matches("[a-f0-9]+");
    }

    private boolean isHtmlResponse(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            return true;
        }

        if (body != null) {
            String lower = body.toLowerCase();
            return lower.contains("<html")
                    || lower.contains("<!doctype html")
                    || lower.contains("grafana");
        }

        return false;
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
