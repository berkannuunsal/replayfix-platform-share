package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.WebhookDeliveryEntity;
import com.etiya.replaylab.model.JiraWebhookEvent;
import com.etiya.replaylab.repository.WebhookDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class JiraWebhookIdempotencyChecker {

    private final WebhookDeliveryRepository repository;

    public JiraWebhookIdempotencyChecker(WebhookDeliveryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IdempotencyResult check(JiraWebhookEvent event) {
        String provider = "JIRA";
        String deliveryId = event.deliveryId();

        if (deliveryId != null && !deliveryId.isBlank()) {
            Optional<WebhookDeliveryEntity> existing =
                    repository.findByProviderAndDeliveryId(provider, deliveryId);

            if (existing.isPresent()) {
                WebhookDeliveryEntity entity = existing.get();
                return new IdempotencyResult(
                        true,
                        entity.getCaseId(),
                        entity.getWorkflowRunId(),
                        entity.getDeliveryId()
                );
            }
        }

        Optional<WebhookDeliveryEntity> fallback =
                repository.findByProviderAndIssueKeyAndEventTypeAndBodySha256(
                        provider,
                        event.issueKey(),
                        event.webhookEvent(),
                        event.bodySha256()
                );

        if (fallback.isPresent()) {
            WebhookDeliveryEntity entity = fallback.get();
            return new IdempotencyResult(
                    true,
                    entity.getCaseId(),
                    entity.getWorkflowRunId(),
                    entity.getDeliveryId()
            );
        }

        return new IdempotencyResult(false, null, null, null);
    }

    @Transactional
    public WebhookDeliveryEntity record(
            JiraWebhookEvent event,
            UUID caseId,
            UUID workflowRunId
    ) {
        WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setProvider("JIRA");
        entity.setDeliveryId(
                event.deliveryId() != null && !event.deliveryId().isBlank()
                        ? event.deliveryId()
                        : "fallback-" + UUID.randomUUID()
        );
        entity.setEventType(event.webhookEvent());
        entity.setIssueKey(event.issueKey());
        entity.setBodySha256(event.bodySha256());
        entity.setStatus("ACCEPTED");
        entity.setCaseId(caseId);
        entity.setWorkflowRunId(workflowRunId);

        return repository.save(entity);
    }

    public record IdempotencyResult(
            boolean duplicate,
            UUID existingCaseId,
            UUID existingWorkflowRunId,
            String existingDeliveryId
    ) {
    }
}
