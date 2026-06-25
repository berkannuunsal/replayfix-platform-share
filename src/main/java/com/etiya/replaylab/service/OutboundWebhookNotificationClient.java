package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.NotificationEntity;
import com.etiya.replaylab.domain.NotificationStatus;
import com.etiya.replaylab.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class OutboundWebhookNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(OutboundWebhookNotificationClient.class);

    private final ReplayLabProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final NotificationRepository notificationRepository;

    public OutboundWebhookNotificationClient(
            ReplayLabProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            NotificationRepository notificationRepository
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.notificationRepository = notificationRepository;
        this.webClient = webClientBuilder.build();
    }

    public void sendAsync(NotificationEntity notification) {
        if (!properties.getNotifications().getWebhook().isEnabled()) {
            return;
        }

        String webhookUrl = properties.getNotifications().getWebhook().getUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        if (!isValidWebhookUrl(webhookUrl)) {
            log.warn("Invalid webhook URL, skipping: {}", webhookUrl);
            return;
        }

        CompletableFuture.runAsync(() -> send(notification));
    }

    private void send(NotificationEntity notification) {
        try {
            String webhookUrl = properties.getNotifications().getWebhook().getUrl();
            String secret = properties.getNotifications().getWebhook().getSecret();
            int maxAttempts = properties.getNotifications().getWebhook().getMaxAttempts();
            int connectTimeout = properties.getNotifications().getWebhook().getConnectTimeoutSeconds();
            int readTimeout = properties.getNotifications().getWebhook().getReadTimeoutSeconds();

            Map<String, Object> payload = Map.of(
                    "eventType", notification.getType().name(),
                    "notificationId", notification.getId().toString(),
                    "caseId", notification.getCaseId() != null ? notification.getCaseId().toString() : "",
                    "workflowRunId", notification.getWorkflowRunId() != null ? notification.getWorkflowRunId().toString() : "",
                    "jiraKey", notification.getJiraKey() != null ? notification.getJiraKey() : "",
                    "title", notification.getTitle(),
                    "message", notification.getMessage() != null ? notification.getMessage() : "",
                    "severity", notification.getSeverity() != null ? notification.getSeverity() : "",
                    "dashboardUrl", notification.getTargetUrl() != null ? notification.getTargetUrl() : "",
                    "createdAt", notification.getCreatedAt().toString()
            );

            String body = objectMapper.writeValueAsString(payload);
            String signature = computeHmacSha256(secret, body);

            notification.setStatus(NotificationStatus.DELIVERY_PENDING);
            notification.setDeliveryAttempt(notification.getDeliveryAttempt() + 1);
            notificationRepository.save(notification);

            webClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .header("X-ReplayLab-Signature", signature)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(connectTimeout + readTimeout))
                    .doOnSuccess(response -> {
                        log.info("Webhook notification delivered: id={}, status={}", 
                                notification.getId(), response.getStatusCode());
                        notification.setStatus(NotificationStatus.DELIVERED);
                        notification.setLastDeliveryError(null);
                        notificationRepository.save(notification);
                    })
                    .doOnError(error -> {
                        log.error("Webhook notification delivery failed: id={}, error={}", 
                                notification.getId(), error.getMessage());
                        notification.setStatus(NotificationStatus.DELIVERY_FAILED);
                        notification.setLastDeliveryError(error.getMessage());
                        notificationRepository.save(notification);
                    })
                    .onErrorResume(error -> Mono.empty())
                    .block();

        } catch (Exception e) {
            log.error("Webhook notification send error: id={}, error={}", 
                    notification.getId(), e.getMessage());
            notification.setStatus(NotificationStatus.DELIVERY_FAILED);
            notification.setLastDeliveryError(e.getMessage());
            notificationRepository.save(notification);
        }
    }

    private boolean isValidWebhookUrl(String url) {
        if (!url.startsWith("https://")) {
            String activeProfiles = System.getProperty("spring.profiles.active", "");
            if (!activeProfiles.contains("local") && !activeProfiles.contains("demo")) {
                return false;
            }
        }

        if (url.contains("localhost") || url.contains("127.0.0.1")) {
            String activeProfiles = System.getProperty("spring.profiles.active", "");
            if (!activeProfiles.contains("local") && !activeProfiles.contains("demo")) {
                return false;
            }
        }

        return true;
    }

    private String computeHmacSha256(String secret, String data) {
        try {
            if (secret == null || secret.isBlank()) {
                return "";
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("HMAC computation failed", e);
            return "";
        }
    }
}
