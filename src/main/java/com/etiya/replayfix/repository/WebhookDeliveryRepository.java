package com.etiya.replayfix.repository;

import com.etiya.replayfix.domain.WebhookDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    Optional<WebhookDeliveryEntity> findByProviderAndDeliveryId(String provider, String deliveryId);

    Optional<WebhookDeliveryEntity> findByProviderAndIssueKeyAndEventTypeAndBodySha256(
            String provider,
            String issueKey,
            String eventType,
            String bodySha256
    );
}
