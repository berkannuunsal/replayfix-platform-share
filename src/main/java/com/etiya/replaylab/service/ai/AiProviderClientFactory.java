package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.AiProviderType;
import org.springframework.stereotype.Component;

@Component
public class AiProviderClientFactory {

    private final ReplayLabProperties properties;
    private final DisabledAiProviderClient disabledProvider;
    private final MockAiProviderClient mockProvider;
    private final CompanyLlmProviderClient companyLlmProvider;

    public AiProviderClientFactory(
            ReplayLabProperties properties,
            DisabledAiProviderClient disabledProvider,
            MockAiProviderClient mockProvider,
            CompanyLlmProviderClient companyLlmProvider
    ) {
        this.properties = properties;
        this.disabledProvider = disabledProvider;
        this.mockProvider = mockProvider;
        this.companyLlmProvider = companyLlmProvider;
    }

    public AiProviderClient getProvider() {
        if (!properties.getAi().isEnabled()) {
            return disabledProvider;
        }

        AiProviderType providerType = properties.getAi().getProvider();
        AiProviderType llmProviderType = properties.getLlm().getProvider();
        if ((providerType == null || providerType == AiProviderType.DISABLED)
                && llmProviderType == AiProviderType.LITELLM_OPENAI_COMPATIBLE) {
            providerType = llmProviderType;
        }
        if (providerType == null) {
            return disabledProvider;
        }

        return switch (providerType) {
            case DISABLED -> disabledProvider;
            case MOCK -> mockProvider;
            case COMPANY_LLM, LITELLM_OPENAI_COMPATIBLE -> companyLlmProvider;
            case OPENAI_COMPATIBLE, LOCAL_PRIVATE -> throw new UnsupportedOperationException(
                    "Provider " + providerType + " not yet implemented in this POC"
            );
        };
    }
}
