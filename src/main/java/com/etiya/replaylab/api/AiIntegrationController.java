package com.etiya.replaylab.api;

import com.etiya.replaylab.model.AiConnectivityResult;
import com.etiya.replaylab.service.ai.AiProviderClient;
import com.etiya.replaylab.service.ai.AiProviderClientFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/ai")
public class AiIntegrationController {

    private final AiProviderClientFactory providerFactory;

    public AiIntegrationController(AiProviderClientFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    @GetMapping("/connectivity")
    public ResponseEntity<AiConnectivityResult> connectivity() {
        try {
            AiProviderClient provider = providerFactory.getProvider();
            AiConnectivityResult result = provider.connectivity();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            String sanitizedError = sanitizeError(e.getMessage());
            return ResponseEntity.ok(new AiConnectivityResult(
                    false,
                    false,
                    "ERROR",
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null,
                    0L,
                    sanitizedError,
                    java.util.List.of("Connectivity check failed: " + sanitizedError)
            ));
        }
    }

    private String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "Connectivity check failed.";
        }
        return message.replaceAll("(?i)(authorization|token|cookie|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }
}
