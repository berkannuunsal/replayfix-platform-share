package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisabledAiProviderClientTest {

    private DisabledAiProviderClient client;

    @BeforeEach
    void setUp() {
        client = new DisabledAiProviderClient();
    }

    @Test
    void testConnectivity_ReturnsDisabled() {
        AiConnectivityResult result = client.connectivity();
        
        assertThat(result.success()).isFalse();
        assertThat(result.enabled()).isFalse();
        assertThat(result.provider()).isEqualTo("DISABLED");
        assertThat(result.warnings()).contains("AI integration is disabled.");
    }

    @Test
    void testGenerate_ReturnsDisabledError() {
        AiGenerationRequest request = new AiGenerationRequest(
                UUID.randomUUID(),
                "FULL_INCIDENT_ANALYSIS",
                "", "", "", 0.1, 30000, true, Map.of()
        );
        
        AiGenerationResponse response = client.generate(request);
        
        assertThat(response.success()).isFalse();
        assertThat(response.provider()).isEqualTo("DISABLED");
        assertThat(response.errorCategory()).isEqualTo("AI_DISABLED");
        assertThat(response.errorMessage()).contains("disabled");
    }

    @Test
    void testProviderName() {
        assertThat(client.providerName()).isEqualTo("DISABLED");
    }

    @Test
    void testSupportsStructuredOutput() {
        assertThat(client.supportsStructuredOutput()).isFalse();
    }
}
