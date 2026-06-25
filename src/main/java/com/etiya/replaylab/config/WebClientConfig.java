package com.etiya.replaylab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {
    @Bean
    WebClient.Builder webClientBuilder() {
        // Configure 4 MB max in-memory size to prevent DataBufferLimitException
        // while still handling large Jenkins responses safely
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(4 * 1024 * 1024) // 4 MB
                )
                .build();

        return WebClient.builder()
                .exchangeStrategies(strategies);
    }
}
