package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.model.IntegrationModels.TempoTrace;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class TempoHttpClient implements TempoClient {
    private final ReplayFixProperties properties;
    private final WebClient.Builder webClientBuilder;

    public TempoHttpClient(ReplayFixProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public TempoTrace getTrace(String traceId) {
        var cfg = properties.getIntegrations().getTempo();
        if (!cfg.isEnabled() || traceId == null || traceId.isBlank()) {
            return new TempoTrace(traceId == null ? "" : traceId, "{}");
        }

        String raw = webClientBuilder.baseUrl(cfg.getBaseUrl()).build().get()
                .uri("/api/traces/{traceId}", traceId)
                .headers(h -> h.addAll(HttpSupport.headers(cfg)))
                .retrieve()
                .bodyToMono(String.class)
                .block(cfg.getTimeout());

        return new TempoTrace(traceId, raw == null ? "{}" : raw);
    }
}
