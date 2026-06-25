package com.etiya.replaylab.integration;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceHttpClientTest {

    private ConfluenceHttpClient client;
    private ReplayLabProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ReplayLabProperties();
        var confluence = new ReplayLabProperties.ConfluenceEndpoint();
        confluence.setEnabled(false);
        confluence.setBaseUrl("https://confluence.test.com");
        confluence.setUsername("test@test.com");
        confluence.setToken("test-token");
        confluence.setMaxSearchResults(15);
        confluence.setMaxPagesPerCase(5);
        properties.getIntegrations().setConfluence(confluence);

        WebClient.Builder webClientBuilder = WebClient.builder();
        ObjectMapper objectMapper = new ObjectMapper();

        client = new ConfluenceHttpClient(properties, webClientBuilder, objectMapper);
    }

    @Test
    void testConnectivityWhenDisabled() {
        ConfluenceConnectivityResult result = client.connectivity();

        assertNotNull(result);
        assertFalse(result.success());
        assertFalse(result.enabled());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("disabled")));
    }

    @Test
    void testSearchWhenDisabled() {
        ConfluenceSearchRequest request = new ConfluenceSearchRequest(
                "type=page AND text~\"test\"",
                10
        );

        ConfluenceSearchResponse response = client.search(request);

        assertNotNull(response);
        assertEquals(0, response.resultCount());
        assertTrue(response.results().isEmpty());
        assertFalse(response.warnings().isEmpty());
    }

    @Test
    void testGetPageWhenDisabled() {
        ConfluencePageDocument page = client.getPage("12345");

        assertNotNull(page);
        assertEquals("12345", page.pageId());
        assertTrue(page.plainText().isEmpty());
        assertFalse(page.warnings().isEmpty());
    }

    @Test
    void testBaseUrlNormalization() {
        properties.getIntegrations().getConfluence().setEnabled(true);
        properties.getIntegrations().getConfluence().setBaseUrl("https://test.atlassian.net/wiki");

        assertDoesNotThrow(() -> {
            client.connectivity();
        });
    }

    @Test
    void testSearchCqlUrlEncoding() {
        ConfluenceSearchRequest request = new ConfluenceSearchRequest(
                "type=page AND text~\"test query\"",
                10
        );

        ConfluenceSearchResponse response = client.search(request);

        assertNotNull(response);
        assertEquals(request.cql(), response.cql());
    }

    @Test
    void testEmptyPageHandling() {
        ConfluencePageDocument page = client.getPage("");

        assertNotNull(page);
        assertEquals("", page.pageId());
    }

    @Test
    void testConfigurationValidation() {
        ConfluenceConnectivityResult result = client.connectivity();

        assertNotNull(result);
        assertTrue(result.baseUrlConfigured());
        assertTrue(result.usernameConfigured());
        assertTrue(result.tokenConfigured());
    }
}
