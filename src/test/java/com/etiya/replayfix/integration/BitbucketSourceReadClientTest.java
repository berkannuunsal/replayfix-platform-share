package com.etiya.replayfix.integration;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BitbucketSourceReadClientTest {

    private final BitbucketSourceReadClient client =
            new BitbucketSourceReadClient(WebClient.builder());

    @Test
    void buildsRawFileUrlForBackendRepository() {
        ReplayFixProperties.SourceCandidateBitbucket bitbucket = bitbucket();
        ReplayFixProperties.SourceCandidateRepository backend = backend();

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
        ReplayFixProperties.SourceCandidateBitbucket bitbucket = bitbucket();
        ReplayFixProperties.SourceCandidateRepository customerUi = customerUi();

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
        ReplayFixProperties.SourceCandidateBitbucket bitbucket = bitbucket();
        bitbucket.setUsernameEnv("__REPLAYFIX_MISSING_BITBUCKET_USERNAME__");
        bitbucket.setTokenEnv("__REPLAYFIX_MISSING_BITBUCKET_TOKEN__");

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
                .doesNotContain("__REPLAYFIX_MISSING_BITBUCKET_USERNAME__")
                .doesNotContain("__REPLAYFIX_MISSING_BITBUCKET_TOKEN__")
                .doesNotContain("BITBUCKET_TOKEN");
    }

    private ReplayFixProperties.SourceCandidateBitbucket bitbucket() {
        ReplayFixProperties.SourceCandidateBitbucket bitbucket =
                new ReplayFixProperties.SourceCandidateBitbucket();
        bitbucket.setBaseUrl("https://bitbucket.etiya.com/");
        bitbucket.setUsernameEnv("BITBUCKET_USERNAME");
        bitbucket.setTokenEnv("BITBUCKET_TOKEN");
        return bitbucket;
    }

    private ReplayFixProperties.SourceCandidateRepository backend() {
        ReplayFixProperties.SourceCandidateRepository repository =
                new ReplayFixProperties.SourceCandidateRepository();
        repository.setLogicalName("backend");
        repository.setProjectKey("DCE");
        repository.setRepositorySlug("backend");
        repository.setBranch("test2");
        repository.setAllowedExtensions(List.of(".java"));
        return repository;
    }

    private ReplayFixProperties.SourceCandidateRepository customerUi() {
        ReplayFixProperties.SourceCandidateRepository repository =
                new ReplayFixProperties.SourceCandidateRepository();
        repository.setLogicalName("customer-ui");
        repository.setProjectKey("DCE");
        repository.setRepositorySlug("customer-ui");
        repository.setBranch("test2");
        repository.setAllowedExtensions(List.of(".ts", ".tsx"));
        return repository;
    }
}
