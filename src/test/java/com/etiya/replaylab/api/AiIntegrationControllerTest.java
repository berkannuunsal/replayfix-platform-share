package com.etiya.replaylab.api;

import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.service.ai.AiProviderClient;
import com.etiya.replaylab.service.ai.AiProviderClientFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiIntegrationControllerTest {

    @Test
    void testConnectivity_MockProvider_Success() {
        AiProviderClientFactory factory = mock(AiProviderClientFactory.class);
        AiProviderClient provider = mock(AiProviderClient.class);
        when(factory.getProvider()).thenReturn(provider);
        when(provider.connectivity()).thenReturn(new AiConnectivityResult(
                true,
                true,
                "MOCK",
                "mock-replaylab-v1",
                true,
                true,
                true,
                true,
                true,
                200,
                0L,
                null,
                List.of("Synthetic AI provider for local validation.")
        ));

        AiConnectivityResult connectivity =
                new AiIntegrationController(factory).connectivity().getBody();

        assertThat(connectivity).isNotNull();
        assertThat(connectivity.success()).isTrue();
        assertThat(connectivity.enabled()).isTrue();
        assertThat(connectivity.provider()).isEqualTo("MOCK");
        assertThat(connectivity.model()).isEqualTo("mock-replaylab-v1");
        assertThat(connectivity.warnings())
                .contains("Synthetic AI provider for local validation.");
    }
}
