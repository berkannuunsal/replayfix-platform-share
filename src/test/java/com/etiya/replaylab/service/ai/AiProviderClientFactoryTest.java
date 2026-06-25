package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.AiProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiProviderClientFactoryTest {

    @Test
    void shouldSelectCompanyLlmProviderFromConfig() {
        ReplayLabProperties properties = new ReplayLabProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.COMPANY_LLM);

        DisabledAiProviderClient disabled = mock(DisabledAiProviderClient.class);
        MockAiProviderClient mockProvider = mock(MockAiProviderClient.class);
        CompanyLlmProviderClient companyProvider =
                mock(CompanyLlmProviderClient.class);

        AiProviderClientFactory factory = new AiProviderClientFactory(
                properties,
                disabled,
                mockProvider,
                companyProvider
        );

        assertThat(factory.getProvider()).isSameAs(companyProvider);
    }

    @Test
    void shouldSelectCompanyLlmProviderFromLiteLlmConfig() {
        ReplayLabProperties properties = new ReplayLabProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider(AiProviderType.DISABLED);
        properties.getLlm().setProvider(
                AiProviderType.LITELLM_OPENAI_COMPATIBLE
        );

        DisabledAiProviderClient disabled = mock(DisabledAiProviderClient.class);
        MockAiProviderClient mockProvider = mock(MockAiProviderClient.class);
        CompanyLlmProviderClient companyProvider =
                mock(CompanyLlmProviderClient.class);

        AiProviderClientFactory factory = new AiProviderClientFactory(
                properties,
                disabled,
                mockProvider,
                companyProvider
        );

        assertThat(factory.getProvider()).isSameAs(companyProvider);
    }
}
