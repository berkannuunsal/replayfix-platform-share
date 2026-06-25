package com.etiya.replaylab.api;

import com.etiya.replaylab.model.DemoWebhookEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/v1/demo/notifications/webhook-receiver")
@Profile("demo")
@ConditionalOnProperty(
        prefix = "replaylab.demo",
        name = "enabled",
        havingValue = "true"
)
public class ReplayLabDemoNotificationWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ReplayLabDemoNotificationWebhookController.class);
    private static final int MAX_EVENTS = 20;

    private final String webhookSecret;
    private final ObjectMapper objectMapper;
    private final List<DemoWebhookEvent> receivedEvents;

    public ReplayLabDemoNotificationWebhookController(
            @Value("${replaylab.notifications.webhook.secret:${replayfix.notifications.webhook.secret:demo-secret-key}}") String webhookSecret,
            ObjectMapper objectMapper
    ) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
        this.receivedEvents = Collections.synchronizedList(new ArrayList<>());
    }

    @PostMapping
    public ResponseEntity<Void> receiveWebhook(
            @RequestHeader(value = "X-ReplayLab-Signature", required = false) String signature,
            @RequestBody String rawBody
    ) {
        if (signature == null || signature.isBlank()) {
            log.warn("Demo webhook receiver: missing X-ReplayLab-Signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean signatureValid = validateSignature(rawBody, signature);

        if (!signatureValid) {
            log.warn("Demo webhook receiver: invalid HMAC signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            
            DemoWebhookEvent event = new DemoWebhookEvent(
                    Instant.now(),
                    maskSignature(signature),
                    true,
                    payload.path("eventType").asText(),
                    payload.path("notificationId").asText(),
                    payload.path("caseId").asText(),
                    payload.path("jiraKey").asText(),
                    payload.path("title").asText(),
                    payload.path("severity").asText()
            );

            synchronized (receivedEvents) {
                receivedEvents.add(0, event);
                if (receivedEvents.size() > MAX_EVENTS) {
                    receivedEvents.remove(receivedEvents.size() - 1);
                }
            }

            log.info("Demo webhook receiver: valid event received - type={}, jiraKey={}", 
                    event.eventType(), event.jiraKey());

        } catch (Exception e) {
            log.error("Demo webhook receiver: failed to parse payload", e);
        }

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    public ResponseEntity<List<DemoWebhookEvent>> getReceivedEvents() {
        synchronized (receivedEvents) {
            return ResponseEntity.ok(new ArrayList<>(receivedEvents));
        }
    }

    private boolean validateSignature(String rawBody, String providedSignature) {
        try {
            String expectedSignature = computeHmacSha256(rawBody, webhookSecret);
            return expectedSignature.equalsIgnoreCase(providedSignature);
        } catch (Exception e) {
            log.error("Demo webhook receiver: HMAC validation failed", e);
            return false;
        }
    }

    private String computeHmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hmacBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String maskSignature(String signature) {
        if (signature == null || signature.length() < 16) {
            return "***";
        }
        return signature.substring(0, 8) + "..." + signature.substring(signature.length() - 8);
    }
}
