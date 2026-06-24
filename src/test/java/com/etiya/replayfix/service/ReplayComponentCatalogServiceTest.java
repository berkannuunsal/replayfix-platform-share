package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayComponentCatalogItem;
import com.etiya.replayfix.config.ReplayFixProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayComponentCatalogServiceTest {

    @Test
    void loadsReplayComponentsYaml() throws Exception {
        ReplayFixProperties properties = loadPropertiesFromYaml();
        ReplayComponentCatalogService service =
                new ReplayComponentCatalogService(properties);

        assertThat(service.all())
                .extracting(ReplayComponentCatalogItem::componentKey)
                .contains("backend", "mco-backend", "serdoo-ui", "csr-ui",
                        "wso2", "mocks");
    }

    @Test
    void findsComponentByKeyAndChecksAllowedMode() throws Exception {
        ReplayComponentCatalogService service =
                new ReplayComponentCatalogService(loadPropertiesFromYaml());

        Optional<ReplayComponentCatalogItem> component =
                service.find("mco-backend");

        assertThat(component).isPresent();
        assertThat(component.get().componentType())
                .isEqualTo("SPRING_BOOT_BACKEND");
        assertThat(service.modeAllowed(component.get(), "REPLAY_COPY")).isTrue();
        assertThat(service.modeAllowed(component.get(), "DISABLED")).isFalse();
    }

    private ReplayFixProperties loadPropertiesFromYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        loader.load(
                        "replay-components",
                        new FileSystemResource("config/replay-components.yml")
                )
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("replayfix", Bindable.of(ReplayFixProperties.class))
                .orElseGet(ReplayFixProperties::new);
    }
}
