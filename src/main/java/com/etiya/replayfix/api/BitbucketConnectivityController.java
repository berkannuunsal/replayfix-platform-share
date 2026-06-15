package com.etiya.replayfix.api;

import com.etiya.replayfix.integration.BitbucketClient;
import com.etiya.replayfix.model.BitbucketConnectionTestResult;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bitbucket")
public class BitbucketConnectivityController {

    private final BitbucketClient bitbucketClient;

    public BitbucketConnectivityController(
            BitbucketClient bitbucketClient
    ) {
        this.bitbucketClient = bitbucketClient;
    }

    @PostMapping("/test")
    public Mono<BitbucketConnectionTestResult> test() {
        return Mono.fromCallable(
                bitbucketClient::testConnection
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @GetMapping("/repositories")
    public Mono<List<BitbucketRepositoryInfo>>
    repositories() {
        return Mono.fromCallable(
                bitbucketClient::listRepositories
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
