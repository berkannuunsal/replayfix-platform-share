package com.etiya.replayfix.api;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.integration.JenkinsClient;
import com.etiya.replayfix.model.JenkinsBuildSnapshot;
import com.etiya.replayfix.model.JenkinsConnectionTestResult;
import com.etiya.replayfix.model.JenkinsJobSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/jenkins")
public class JenkinsConnectivityController {

    private final JenkinsClient jenkinsClient;
    private final ReplayFixProperties properties;

    public JenkinsConnectivityController(
            JenkinsClient jenkinsClient,
            ReplayFixProperties properties
    ) {
        this.jenkinsClient = jenkinsClient;
        this.properties = properties;
    }

    @GetMapping("/test")
    public Mono<JenkinsConnectionTestResult> test() {
        return Mono.fromCallable(
                jenkinsClient::testConnection
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @GetMapping("/applications")
    public Set<String> applications() {
        return properties.getIntegrations()
                .getJenkins()
                .getApplications()
                .keySet();
    }

    @GetMapping("/jobs/{application}/{jobType}")
    public Mono<JenkinsJobSnapshot> job(
            @PathVariable String application,
            @PathVariable String jobType
    ) {
        var applications = properties.getIntegrations()
                .getJenkins()
                .getApplications();

        var applicationConfig =
                applications.get(application);

        if (applicationConfig == null) {
            throw new IllegalArgumentException(
                    "Unknown Jenkins application: "
                            + application
            );
        }

        String jobUrl = switch (
                jobType.toLowerCase()
        ) {
            case "build" ->
                    applicationConfig.getBuildJobUrl();

            case "image" ->
                    applicationConfig.getImageJobUrl();

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported Jenkins job type: "
                                    + jobType
                                    + ". Supported values: build, image"
                    );
        };

        if (jobUrl == null || jobUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Jenkins "
                            + jobType
                            + " job URL is not configured for: "
                            + application
            );
        }

        return Mono.fromCallable(() ->
                jenkinsClient.readJob(jobUrl)
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }

    @GetMapping(
            "/jobs/{application}/{jobType}/last-success"
    )
    public Mono<JenkinsBuildSnapshot> lastSuccess(
            @PathVariable String application,
            @PathVariable String jobType
    ) {
        var applications =
                properties.getIntegrations()
                        .getJenkins()
                        .getApplications();

        var applicationConfig =
                applications.get(application);

        if (applicationConfig == null) {
            throw new IllegalArgumentException(
                    "Unknown Jenkins application: "
                            + application
            );
        }

        String jobUrl = switch (
                jobType.toLowerCase()
        ) {
            case "build" ->
                    applicationConfig.getBuildJobUrl();

            case "image" ->
                    applicationConfig.getImageJobUrl();

            default ->
                    throw new IllegalArgumentException(
                            "Supported job types: build, image"
                    );
        };

        if (jobUrl == null || jobUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Job URL is not configured for "
                            + application
                            + "/"
                            + jobType
            );
        }

        return Mono.fromCallable(() ->
                jenkinsClient
                        .readLastSuccessfulBuild(
                                jobUrl
                        )
        ).subscribeOn(
                Schedulers.boundedElastic()
        );
    }
}
