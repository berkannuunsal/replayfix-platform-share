package com.etiya.replayfix.api;

import com.etiya.replayfix.integration.AiClient;
import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiConnectivityControllerTest {

    @Test
    void shouldExposeProviderConnectivity() {
        AiClient legacyClient = mock(AiClient.class);
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        when(provider.connectivity()).thenReturn(new AiConnectivityResult(
                true,
                true,
                "COMPANY_LLM",
                "company-model",
                true,
                true,
                true,
                true,
                true,
                200,
                12L,
                null,
                List.of()
        ));

        AiConnectivityResult result = new AiConnectivityController(
                legacyClient,
                factory
        ).connectivity().block();

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("COMPANY_LLM");
        assertThat(result.baseUrlConfigured()).isTrue();
        assertThat(result.tokenConfigured()).isTrue();
        assertThat(result.reachable()).isTrue();
    }
}
