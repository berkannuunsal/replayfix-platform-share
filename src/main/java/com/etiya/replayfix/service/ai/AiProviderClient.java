package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;

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
