package com.etiya.replayfix.service.ai;

import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.model.AiGenerationRequest;
import com.etiya.replayfix.model.AiGenerationResponse;

public interface AiProviderClient {
    
    AiConnectivityResult connectivity();
    
    AiGenerationResponse generate(AiGenerationRequest request);
    
    String providerName();
    
    boolean supportsStructuredOutput();
}
