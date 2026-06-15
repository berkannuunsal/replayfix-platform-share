package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConfigValidationService {
    private final ReplayFixProperties properties;

    public ConfigValidationService(ReplayFixProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> validate() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", properties.getMode());
        result.put("workspaceWritable", writable(properties.getWorkspaceDir()));
        result.put("targets", validateTargets());
        result.put("integrations", Map.of(
                "jira", endpoint(properties.getIntegrations().getJira()),
                "loki", endpoint(properties.getIntegrations().getLoki()),
                "tempo", endpoint(properties.getIntegrations().getTempo()),
                "bitbucket", endpoint(properties.getIntegrations().getBitbucket()),
                "confluence", endpoint(properties.getIntegrations().getConfluence()),
                "ai", endpoint(properties.getIntegrations().getAi()),
                "jenkins", endpoint(properties.getIntegrations().getJenkins()),
                "kubernetesEnabled",
                properties.getIntegrations().getKubernetes().isEnabled()
        ));
        return result;
    }

    private Map<String, Object> validateTargets() {
        Map<String, Object> targets = new LinkedHashMap<>();

        properties.getTargets().forEach((key, target) -> {
            List<String> errors = new ArrayList<>();

            if (target.getRepository().isBlank()) {
                errors.add("repository is missing");
            }
            if (target.getImage().isBlank()) {
                errors.add("image is missing");
            }
            if (!target.getDatabase().getSchemaFile().isBlank()
                    && !Files.exists(Path.of(target.getDatabase().getSchemaFile()))) {
                errors.add("schemaFile not found");
            }
            if (!target.getDatabase().getSeedFile().isBlank()
                    && !Files.exists(Path.of(target.getDatabase().getSeedFile()))) {
                errors.add("seedFile not found");
            }
            if (!target.getReplay().getRequestFile().isBlank()
                    && !Files.exists(Path.of(target.getReplay().getRequestFile()))) {
                errors.add("requestFile not found");
            }

            targets.put(key, Map.of(
                    "valid", errors.isEmpty(),
                    "errors", errors
            ));
        });

        return targets;
    }

    private Map<String, Object> endpoint(
            ReplayFixProperties.Endpoint endpoint
    ) {
        return Map.of(
                "enabled", endpoint.isEnabled(),
                "baseUrlConfigured",
                endpoint.getBaseUrl() != null
                        && !endpoint.getBaseUrl().isBlank(),
                "tokenConfigured",
                endpoint.getToken() != null
                        && !endpoint.getToken().isBlank()
        );
    }

    private boolean writable(String directory) {
        try {
            Path path = Path.of(directory);
            Files.createDirectories(path);
            return Files.isWritable(path);
        } catch (Exception e) {
            return false;
        }
    }
}
