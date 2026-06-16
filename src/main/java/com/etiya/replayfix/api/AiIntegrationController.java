package com.etiya.replayfix.api;

import com.etiya.replayfix.model.AiConnectivityResult;
import com.etiya.replayfix.service.ai.AiProviderClient;
import com.etiya.replayfix.service.ai.AiProviderClientFactory;
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
            return ResponseEntity.ok(new AiConnectivityResult(
                    false,
                    false,
                    "ERROR",
                    null,
                    false,
                    false,
                    null,
                    0L,
                    java.util.List.of("Connectivity check failed: " + e.getMessage())
            ));
        }
    }
}
