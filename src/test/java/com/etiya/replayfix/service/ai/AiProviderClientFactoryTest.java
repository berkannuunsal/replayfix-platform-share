package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiProviderClientFactoryTest {

    @Test
    void shouldSelectCompanyLlmProviderFromConfig() {
        ReplayFixProperties properties = new ReplayFixProperties();
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
}
