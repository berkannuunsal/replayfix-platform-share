package com.etiya.replaylab.service.ai;

import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.model.AiGenerationRequest;
import com.etiya.replaylab.model.AiGenerationResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("disabledAiProvider")
public class DisabledAiProviderClient implements AiProviderClient {

    @Override
    public AiConnectivityResult connectivity() {
        return new AiConnectivityResult(
                false,
                false,
                "DISABLED",
                null,
                false,
                false,
                false,
                false,
                false,
                null,
                0L,
                "AI integration is disabled.",
                List.of("AI integration is disabled.")
        );
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        return new AiGenerationResponse(
                false,
                "DISABLED",
                null,
                null,
                "disabled",
                0L,
                0,
                0,
                null,
                List.of("AI integration is disabled."),
                "AI_DISABLED",
                "AI integration is disabled. Enable it in configuration."
        );
    }

    @Override
    public String providerName() {
        return "DISABLED";
    }

    @Override
    public boolean supportsStructuredOutput() {
        return false;
    }
}
