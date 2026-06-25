package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;

public interface AiProviderClient {
    
    AiConnectivityResult connectivity();

    default AiConnectivityResult connectivity(
            String modelProfile,
            String modelName
    ) {
        return connectivity();
    }
    
    AiGenerationResponse generate(AiGenerationRequest request);
    
    String providerName();
    
    boolean supportsStructuredOutput();
}
