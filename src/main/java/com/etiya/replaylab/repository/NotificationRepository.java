package com.etiya.replaylab.repository;

import com.etiya.replaylab.domain.NotificationEntity;
import com.etiya.replaylab.domain.NotificationStatus;
import com.etiya.replaylab.domain.NotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByStatusOrderByCreatedAtDesc(NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);

    Optional<NotificationEntity> findFirstByCaseIdAndTypeOrderByCreatedAtDesc(UUID caseId, NotificationType type);
}
