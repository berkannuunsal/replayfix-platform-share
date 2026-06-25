package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JiraWebhookSecurityValidatorTest {

    private JiraWebhookSecurityValidator validator;
    private ReplayLabProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ReplayLabProperties();
        ReplayLabProperties.Integrations integrations = new ReplayLabProperties.Integrations();
        ReplayLabProperties.JiraWebhookEndpoint webhook = new ReplayLabProperties.JiraWebhookEndpoint();

        webhook.setEnabled(true);
        webhook.setSecret("TEST_SECRET");
        webhook.setAllowedEventTypes(List.of("jira:issue_created", "jira:issue_updated"));
        webhook.setAllowedProjectKeys("FIZZMS,TEST");
        webhook.setAllowedIssueTypes("Bug,Defect");
        webhook.setMaxBodyChars(1000000);
        webhook.setReplayWindowSeconds(300);

        integrations.setJiraWebhook(webhook);
        properties.setIntegrations(integrations);

        validator = new JiraWebhookSecurityValidator(properties);
    }

    @Test
    void shouldAcceptValidWebhook() {
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_created",
                "FIZZMS",
                "Bug",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertTrue(result.valid());
        assertNull(result.errorMessage());
    }

    @Test
    void shouldRejectInvalidSecret() {
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                "WRONG_SECRET",
                "jira:issue_created",
                "FIZZMS",
                "Bug",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertEquals("Invalid webhook secret", result.errorMessage());
    }

    @Test
    void shouldRejectMissingSecret() {
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                null,
                "jira:issue_created",
                "FIZZMS",
                "Bug",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertEquals("Missing webhook secret header", result.errorMessage());
    }

    @Test
    void shouldRejectOldTimestamp() {
        String oldTimestamp = Instant.now().minusSeconds(400).toString();

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_created",
                "FIZZMS",
                "Bug",
                oldTimestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertEquals("Event timestamp outside replay window", result.errorMessage());
    }

    @Test
    void shouldRejectBodySizeExceeded() {
        String timestamp = Instant.now().toString();
        String largeBody = "x".repeat(1000001);

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_created",
                "FIZZMS",
                "Bug",
                timestamp,
                largeBody
        );

        assertFalse(result.valid());
        assertEquals("Body size exceeds limit", result.errorMessage());
    }

    @Test
    void shouldRejectDisallowedEventType() {
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_deleted",
                "FIZZMS",
                "Bug",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Event type not allowed"));
    }

    @Test
    void shouldRejectDisallowedProject() {
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_created",
                "NOTALLOWED",
                "Bug",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Project key not allowed"));
    }

    @Test
    void shouldRejectDisallowedIssueType() {
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_created",
                "FIZZMS",
                "Story",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Issue type not allowed"));
    }

    @Test
    void shouldRejectWhenDisabled() {
        properties.getIntegrations().getJiraWebhook().setEnabled(false);
        String timestamp = Instant.now().toString();

        var result = validator.validate(
                "TEST_SECRET",
                "jira:issue_created",
                "FIZZMS",
                "Bug",
                timestamp,
                "{\"test\":\"data\"}"
        );

        assertFalse(result.valid());
        assertEquals("Jira webhook is disabled", result.errorMessage());
    }

    @Test
    void shouldComputeConsistentBodyHash() {
        String body = "{\"test\":\"data\"}";

        String hash1 = validator.computeBodyHash(body);
        String hash2 = validator.computeBodyHash(body);

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }

    @Test
    void shouldComputeDifferentHashesForDifferentBodies() {
        String body1 = "{\"test\":\"data1\"}";
        String body2 = "{\"test\":\"data2\"}";

        String hash1 = validator.computeBodyHash(body1);
        String hash2 = validator.computeBodyHash(body2);

        assertNotEquals(hash1, hash2);
    }
}
