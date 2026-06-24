package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayComponentCatalogItem(
        String componentKey,
        String displayName,
        String componentType,
        String repositoryProject,
        String repositorySlug,
        String defaultBranch,
        String gitOpsRepo,
        String helmChartPath,
        String valuesPath,
        String imageRepository,
        String defaultNamespace,
        String healthPath,
        int servicePort,
        String ownerTeam,
        List<String> dependencyModes,
        boolean allowReplay,
        boolean allowLoadTest,
        boolean requiresApproval
) {
    public ReplayComponentCatalogItem {
        componentKey = safe(componentKey);
        displayName = safe(displayName);
        componentType = safe(componentType);
        repositoryProject = safe(repositoryProject);
        repositorySlug = safe(repositorySlug);
        defaultBranch = safe(defaultBranch);
        gitOpsRepo = safe(gitOpsRepo);
        helmChartPath = safe(helmChartPath);
        valuesPath = safe(valuesPath);
        imageRepository = safe(imageRepository);
        defaultNamespace = safe(defaultNamespace);
        healthPath = safe(healthPath);
        ownerTeam = safe(ownerTeam);
        dependencyModes = dependencyModes == null
                ? List.of()
                : List.copyOf(dependencyModes);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
