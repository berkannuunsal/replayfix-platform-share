package com.etiya.replaylab.integration;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class HttpSupport {
    private HttpSupport() {}

    public static HttpHeaders headers(ReplayLabProperties.Endpoint endpoint) {
        HttpHeaders headers = new HttpHeaders();
        if (!StringUtils.hasText(endpoint.getToken())) return headers;
        if ("BASIC".equalsIgnoreCase(endpoint.getAuthType())) {
            String raw = endpoint.getUsername() + ":" + endpoint.getToken();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.setBearerAuth(endpoint.getToken());
        }
        return headers;
    }
}
