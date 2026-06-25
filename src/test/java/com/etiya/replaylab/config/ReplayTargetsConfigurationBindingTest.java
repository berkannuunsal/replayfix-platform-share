package com.etiya.replaylab.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayTargetsConfigurationBindingTest {

    @Test
    void applicationImportsReplayTargetsConfig() throws Exception {
        String applicationYaml = Files.readString(
                Path.of("src/main/resources/application.yml")
        );

        assertThat(applicationYaml)
                .contains("optional:file:./config/replay-targets.yml");
    }

    @Test
    void bssMonolithTargetBindsFromReplayTargetsYaml() throws Exception {
        ReplayLabProperties properties = bindReplayTargets();
        ReplayLabProperties.Target target =
                properties.getTargets().get("bss-monolith");

        assertThat(target).isNotNull();
        assertThat(target.getApplicationKey()).isEqualTo("bss-monolith");
        assertThat(target.getBackendArgoCdApplicationName())
                .isEqualTo("bss-backend");
        assertThat(target.getBackendChartPath()).isEqualTo("bss-backend");
        assertThat(target.getBackendTargetRevision()).isEqualTo("test2");
        assertThat(target.getBackendNamespace())
                .isEqualTo("project-test-bss-backend");
        assertThat(target.getCustomerUiArgoCdApplicationName())
                .isEqualTo("frontend-customer-ui");
        assertThat(target.getPreCreatedReplayNamespace())
                .isEqualTo("project-replay-sandbox");
        assertThat(target.getDbStateMode()).isEqualTo("TEST2_SHARED_DB");
        assertThat(target.isStateContinuationRequested()).isTrue();
        assertThat(target.getRequiredDbSecretKeys())
                .contains("BSS_DB_URL", "BSS_DB_USER", "BSS_DB_PASSWORD");
        assertThat(target.getAccessMode()).isEqualTo("SINGLE_HOST_PATH_BASED");
        assertThat(target.getBackendContextPath())
                .isEqualTo("/DCE-CommerceBackend");
        assertThat(target.getCustomerUiBackendBaseUrlConfigKey())
                .isEqualTo("CUSTOMER_UI_BACKEND_BASE_URL");
        assertThat(target.getExternalDependencies())
                .containsKeys("crmAssetAuth", "email", "activeMq", "kafka", "redis");
        assertThat(target.getDbSampleDomains()).containsKey("customer");
        assertThat(target.isSourceCandidateExtractionEnabled()).isTrue();
        assertThat(target.getSourceCandidateSource()).isEqualTo("BITBUCKET");
        assertThat(target.getSourceCandidateExtractionBranch()).isEqualTo("test2");
        assertThat(target.getAllowedSourceExtensions())
                .contains(".java", ".ts", ".tsx");
        assertThat(target.getMaxSourceCandidateFiles()).isEqualTo(5);
        assertThat(target.getMaxSnippetChars()).isEqualTo(12000);
        assertThat(target.getBitbucket().getBaseUrl())
                .contains("bitbucket.company.com");
        assertThat(target.getBitbucket().getUsernameEnv())
                .isEqualTo("BITBUCKET_USERNAME");
        assertThat(target.getBitbucket().getTokenEnv())
                .isEqualTo("BITBUCKET_TOKEN");
        assertThat(target.getBitbucket().getRepositories())
                .containsKeys("backend", "customerUi");
        assertThat(target.getBitbucket().getRepositories()
                .get("backend").getRepositorySlug()).isEqualTo("backend");
        assertThat(target.getBitbucket().getRepositories()
                .get("customerUi").getRepositorySlug())
                .isEqualTo("customer-ui");
    }

    @Test
    void bssMonolithTargetDoesNotContainSecretValues() throws Exception {
        ReplayLabProperties.Target target =
                bindReplayTargets().getTargets().get("bss-monolith");

        String rendered = target.getEnvironment().toString()
                + target.getExternalDependencies().toString()
                + target.getDbSampleDomains().toString();

        assertThat(rendered)
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("PRIVATE KEY")
                .doesNotContain("BEGIN");
    }

    private ReplayLabProperties bindReplayTargets() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> loaded = loader.load(
                "replay-targets",
                new FileSystemResource("config/replay-targets.yml")
        );
        MutablePropertySources propertySources = new MutablePropertySources();
        loaded.forEach(propertySources::addLast);
        Iterable<ConfigurationPropertySource> configurationSources =
                ConfigurationPropertySources.from(propertySources);
        return new Binder(configurationSources)
                .bind("replaylab", ReplayLabProperties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "replaylab properties did not bind"
                ));
    }
}
