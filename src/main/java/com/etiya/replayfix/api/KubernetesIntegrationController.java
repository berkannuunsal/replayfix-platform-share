package com.etiya.replayfix.api;

import com.etiya.replayfix.model.KubernetesConnectivityCheck;
import com.etiya.replayfix.service.KubernetesConnectivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/integrations/kubernetes")
public class KubernetesIntegrationController {

    private final KubernetesConnectivityService connectivityService;

    public KubernetesIntegrationController(
            KubernetesConnectivityService connectivityService
    ) {
        this.connectivityService = connectivityService;
    }

    @GetMapping("/connectivity")
    public Mono<KubernetesConnectivityCheck> checkConnectivity() {
        return Mono.fromCallable(connectivityService::checkConnectivity)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
