package com.etiya.replaylab.api;

import com.etiya.replaylab.model.SourceCheckoutResult;
import com.etiya.replaylab.service.RepositoryCheckoutService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class RepositoryCheckoutController {

    private final RepositoryCheckoutService checkoutService;

    public RepositoryCheckoutController(
            RepositoryCheckoutService checkoutService
    ) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/{id}/checkout-source")
    public Mono<SourceCheckoutResult> checkout(
            @PathVariable UUID id
    ) {
        return Mono.fromCallable(() ->
                checkoutService.checkout(id)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
