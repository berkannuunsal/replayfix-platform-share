package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.domain.AiProviderType;
import org.springframework.stereotype.Component;

@Component
public class AiProviderClientFactory {

    private final ReplayFixProperties properties;
    private final DisabledAiProviderClient disabledProvider;
    private final MockAiProviderClient mockProvider;

    public AiProviderClientFactory(
            ReplayFixProperties properties,
            DisabledAiProviderClient disabledProvider,
            MockAiProviderClient mockProvider
    ) {
        this.properties = properties;
        this.disabledProvider = disabledProvider;
        this.mockProvider = mockProvider;
    }

    public AiProviderClient getProvider() {
        if (!properties.getAi().isEnabled()) {
            return disabledProvider;
        }

        AiProviderType providerType = properties.getAi().getProvider();
        if (providerType == null) {
            return disabledProvider;
        }

        return switch (providerType) {
            case DISABLED -> disabledProvider;
            case MOCK -> mockProvider;
            case OPENAI_COMPATIBLE, LOCAL_PRIVATE -> throw new UnsupportedOperationException(
                    "Provider " + providerType + " not yet implemented in this POC"
            );
        };
    }
}
