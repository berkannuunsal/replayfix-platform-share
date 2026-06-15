package com.etiya.replayfix.service;

import com.etiya.replayfix.config.RepositoryResolutionProperties;
import com.etiya.replayfix.config.RepositoryResolutionProperties.RepositoryRule;
import com.etiya.replayfix.model.BitbucketRepositoryInfo;
import com.etiya.replayfix.model.IncidentSignals;
import com.etiya.replayfix.model.IntegrationModels.JiraIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryResolverServiceTest {

    @Test
    void selectsBackendForCustomerOrderFailure() {
        RepositoryResolutionProperties properties =
                new RepositoryResolutionProperties();

        RepositoryRule backendRule =
                new RepositoryRule();
        backendRule.setAliases(
                List.of(
                        "bss-backend",
                        "customerorder",
                        "bar",
                        "payment"
                )
        );
        backendRule.setEndpointPrefixes(
                List.of("/customerorder")
        );

        RepositoryRule ntfRule =
                new RepositoryRule();
        ntfRule.setAliases(
                List.of(
                        "ntf",
                        "notification"
                )
        );

        properties.setMappings(
                Map.of(
                        "backend",
                        backendRule,
                        "ntf-engine",
                        ntfRule
                )
        );

        RepositoryResolverService resolver =
                new RepositoryResolverService(
                        properties
                );

        var repositories = List.of(
                new BitbucketRepositoryInfo(
                        "DCE",
                        "backend",
                        "backend",
                        "develop",
                        "AVAILABLE",
                        false,
                        "https://bitbucket/scm/DCE/backend.git"
                ),
                new BitbucketRepositoryInfo(
                        "DCE",
                        "ntf-engine",
                        "NTF-Engine",
                        "develop",
                        "AVAILABLE",
                        false,
                        "https://bitbucket/scm/DCE/ntf-engine.git"
                )
        );

        IncidentSignals signals = new IncidentSignals(
                List.of(
                        "/customerorder/omintegration/complete"
                ),
                List.of("401"),
                List.of("9999"),
                List.of(
                        "BAR",
                        "payment"
                ),
                List.of("12"),
                List.of("bss-backend")
        );

        JiraIssue issue = new JiraIssue(
                "FIZZMS-8346",
                "BAR customer order receives 401",
                "",
                Map.of()
        );

        var result = resolver.resolve(
                repositories,
                issue,
                "Customer order completion failed.",
                signals,
                null
        );

        assertThat(
                result.primaryRepositorySlug()
        ).isEqualTo("backend");

        assertThat(result.candidates())
                .isNotEmpty();

        assertThat(
                result.candidates().get(0).score()
        ).isGreaterThan(100);
    }
}
