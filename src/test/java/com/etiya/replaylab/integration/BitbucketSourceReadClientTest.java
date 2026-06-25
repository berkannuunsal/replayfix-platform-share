package com.etiya.replaylab.integration;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BitbucketSourceReadClientTest {

    private final BitbucketSourceReadClient client =
            new BitbucketSourceReadClient(WebClient.builder());

    @Test
    void buildsRawFileUrlForBackendRepository() {
        ReplayLabProperties.SourceCandidateBitbucket bitbucket = bitbucket();
        ReplayLabProperties.SourceCandidateRepository backend = backend();

        String url = client.buildRawFileUrl(
                bitbucket,
                backend,
                "src/main/java/com/acme/OrderService.java",
                "test2"
        );

        assertThat(url).isEqualTo(
                "https://bitbucket.etiya.com/rest/api/1.0/projects/DCE"
                        + "/repos/backend/raw/src/main/java/com/acme"
                        + "/OrderService.java?at=refs/heads/test2"
        );
    }

    @Test
    void buildsRawFileUrlForCustomerUiRepository() {
        ReplayLabProperties.SourceCandidateBitbucket bitbucket = bitbucket();
        ReplayLabProperties.SourceCandidateRepository customerUi = customerUi();

        String url = client.buildRawFileUrl(
                bitbucket,
                customerUi,
                "src/app/customer/CustomerPanel.tsx",
                "test2"
        );

        assertThat(url).isEqualTo(
                "https://bitbucket.etiya.com/rest/api/1.0/projects/DCE"
                        + "/repos/customer-ui/raw/src/app/customer"
                        + "/CustomerPanel.tsx?at=refs/heads/test2"
        );
    }

    @Test
    void missingCredentialsReturnSafeFailureWithoutCredentialValues() {
        ReplayLabProperties.SourceCandidateBitbucket bitbucket = bitbucket();
        bitbucket.setUsernameEnv("__REPLAYLAB_MISSING_BITBUCKET_USERNAME__");
        bitbucket.setTokenEnv("__REPLAYLAB_MISSING_BITBUCKET_TOKEN__");

        BitbucketSourceReadClient.SourceFileFetchResult result =
                client.fetchRawFile(
                        bitbucket,
                        backend(),
                        "src/main/java/com/acme/OrderService.java",
                        "test2"
                );

        assertThat(result.success()).isFalse();
        assertThat(result.status())
                .isEqualTo("BITBUCKET_CREDENTIALS_NOT_CONFIGURED");
        assertThat(result.toString())
                .doesNotContain("__REPLAYLAB_MISSING_BITBUCKET_USERNAME__")
                .doesNotContain("__REPLAYLAB_MISSING_BITBUCKET_TOKEN__")
                .doesNotContain("BITBUCKET_TOKEN");
    }

    private ReplayLabProperties.SourceCandidateBitbucket bitbucket() {
        ReplayLabProperties.SourceCandidateBitbucket bitbucket =
                new ReplayLabProperties.SourceCandidateBitbucket();
        bitbucket.setBaseUrl("https://bitbucket.etiya.com/");
        bitbucket.setUsernameEnv("BITBUCKET_USERNAME");
        bitbucket.setTokenEnv("BITBUCKET_TOKEN");
        return bitbucket;
    }

    private ReplayLabProperties.SourceCandidateRepository backend() {
        ReplayLabProperties.SourceCandidateRepository repository =
                new ReplayLabProperties.SourceCandidateRepository();
        repository.setLogicalName("backend");
        repository.setProjectKey("DCE");
        repository.setRepositorySlug("backend");
        repository.setBranch("test2");
        repository.setAllowedExtensions(List.of(".java"));
        return repository;
    }

    private ReplayLabProperties.SourceCandidateRepository customerUi() {
        ReplayLabProperties.SourceCandidateRepository repository =
                new ReplayLabProperties.SourceCandidateRepository();
        repository.setLogicalName("customer-ui");
        repository.setProjectKey("DCE");
        repository.setRepositorySlug("customer-ui");
        repository.setBranch("test2");
        repository.setAllowedExtensions(List.of(".ts", ".tsx"));
        return repository;
    }
}
