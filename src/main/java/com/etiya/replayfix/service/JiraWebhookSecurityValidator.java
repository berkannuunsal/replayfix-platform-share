package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@Service
public class JiraWebhookSecurityValidator {

    private final ReplayFixProperties properties;

    public JiraWebhookSecurityValidator(ReplayFixProperties properties) {
        this.properties = properties;
    }

    public ValidationResult validate(
            String headerSecret,
            String eventType,
            String projectKey,
            String issueType,
            String eventTimestamp,
            String body
    ) {
        var config = properties.getIntegrations().getJiraWebhook();

        if (!config.isEnabled()) {
            return new ValidationResult(false, "Jira webhook is disabled");
        }

        if (body == null || body.length() > config.getMaxBodyChars()) {
            return new ValidationResult(false, "Body size exceeds limit");
        }

        String configuredSecret = config.getSecret();
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            if (headerSecret == null || headerSecret.isBlank()) {
                return new ValidationResult(false, "Missing webhook secret header");
            }
            if (!configuredSecret.equals(headerSecret)) {
                return new ValidationResult(false, "Invalid webhook secret");
            }
        }

        if (eventTimestamp != null && !eventTimestamp.isBlank()) {
            try {
                Instant eventTime = Instant.from(
                        DateTimeFormatter.ISO_INSTANT.parse(eventTimestamp)
                );
                long secondsAgo = Instant.now().getEpochSecond() - eventTime.getEpochSecond();
                if (Math.abs(secondsAgo) > config.getReplayWindowSeconds()) {
                    return new ValidationResult(false, "Event timestamp outside replay window");
                }
            } catch (Exception e) {
                return new ValidationResult(false, "Invalid event timestamp format");
            }
        }

        if (!config.getAllowedEventTypes().contains(eventType)) {
            return new ValidationResult(false, "Event type not allowed: " + eventType);
        }

        List<String> allowedProjects = parseCommaSeparated(config.getAllowedProjectKeys());
        if (!allowedProjects.isEmpty() && !allowedProjects.contains(projectKey)) {
            return new ValidationResult(false, "Project key not allowed: " + projectKey);
        }

        List<String> allowedIssueTypes = parseCommaSeparated(config.getAllowedIssueTypes());
        if (!allowedIssueTypes.isEmpty() && !allowedIssueTypes.contains(issueType)) {
            return new ValidationResult(false, "Issue type not allowed: " + issueType);
        }

        return new ValidationResult(true, null);
    }

    public String computeBodyHash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute body hash", e);
        }
    }

    private List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public record ValidationResult(boolean valid, String errorMessage) {
    }
}
