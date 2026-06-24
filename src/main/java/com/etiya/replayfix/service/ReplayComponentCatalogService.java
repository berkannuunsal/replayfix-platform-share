package com.etiya.replayfix.service;

import com.etiya.replayfix.api.dto.ReplayComponentCatalogItem;
import com.etiya.replayfix.config.ReplayFixProperties;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class ReplayComponentCatalogService {

    private final ReplayFixProperties properties;

    public ReplayComponentCatalogService(ReplayFixProperties properties) {
        this.properties = properties;
    }

    public List<ReplayComponentCatalogItem> all() {
        return properties.getComponents().entrySet().stream()
                .map(entry -> item(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ReplayComponentCatalogItem::componentKey))
                .toList();
    }

    public Optional<ReplayComponentCatalogItem> find(String componentKey) {
        String key = normalizeKey(componentKey);
        ReplayFixProperties.ReplayComponent component =
                properties.getComponents().get(key);
        return component == null
                ? Optional.empty()
                : Optional.of(item(key, component));
    }

    public boolean modeAllowed(
            ReplayComponentCatalogItem component,
            String requestedMode
    ) {
        String mode = normalizeMode(requestedMode);
        return component.dependencyModes().stream()
                .map(this::normalizeMode)
                .anyMatch(mode::equals);
    }

    public String normalizeKey(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeMode(String value) {
        return value == null
                ? "UNKNOWN"
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private ReplayComponentCatalogItem item(
            String fallbackKey,
            ReplayFixProperties.ReplayComponent component
    ) {
        String key = firstNonBlank(component.getComponentKey(), fallbackKey);
        return new ReplayComponentCatalogItem(
                normalizeKey(key),
                component.getDisplayName(),
                firstNonBlank(component.getComponentType(), "UNKNOWN"),
                component.getRepositoryProject(),
                component.getRepositorySlug(),
                component.getDefaultBranch(),
                component.getGitOpsRepo(),
                component.getHelmChartPath(),
                component.getValuesPath(),
                component.getImageRepository(),
                component.getDefaultNamespace(),
                component.getHealthPath(),
                component.getServicePort(),
                component.getOwnerTeam(),
                component.getDependencyModes(),
                component.isAllowReplay(),
                component.isAllowLoadTest(),
                component.isRequiresApproval()
        );
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
