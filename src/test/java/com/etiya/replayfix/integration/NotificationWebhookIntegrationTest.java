package com.etiya.replayfix.integration;

import com.etiya.replayfix.domain.NotificationEntity;
import com.etiya.replayfix.domain.NotificationStatus;
import com.etiya.replayfix.domain.NotificationType;
import com.etiya.replayfix.repository.NotificationRepository;
import com.etiya.replayfix.service.OutboundWebhookNotificationClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = {
        "replayfix.notifications.webhook.enabled=true",
        "replayfix.notifications.webhook.url=http://localhost:8088/api/v1/demo/notifications/webhook-receiver",
        "replayfix.notifications.webhook.secret=demo-secret-key"
})
class NotificationWebhookIntegrationTest {

    @Autowired
    private OutboundWebhookNotificationClient webhookClient;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void testWebhookDelivery_UpdatesStatusToDelivered() throws InterruptedException {
        NotificationEntity notification = new NotificationEntity();
        notification.setCaseId(UUID.randomUUID());
        notification.setType(NotificationType.WORKFLOW_SUCCESS);
        notification.setStatus(NotificationStatus.DELIVERY_PENDING);
        notification.setTitle("Test workflow completed");
        notification.setMessage("Workflow completed successfully");
        notification.setSeverity("INFO");
        notification.setTargetUrl("/replayfix/?caseId=" + notification.getCaseId());
        notification.setJiraKey("TEST-123");
        
        notification = notificationRepository.save(notification);
        UUID notificationId = notification.getId();

        webhookClient.sendAsync(notification);

        Thread.sleep(3000);

        NotificationEntity updated = notificationRepository.findById(notificationId).orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(updated.getLastDeliveryError()).isNull();
    }
}
