package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.*;
import com.etiya.replayfix.model.NotificationCommand;
import com.etiya.replayfix.model.NotificationView;
import com.etiya.replayfix.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReplayFixNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ReplayFixNotificationService.class);

    private final NotificationRepository notificationRepository;
    private final EvidenceSanitizer sanitizer;
    private final OutboundWebhookNotificationClient webhookClient;

    public ReplayFixNotificationService(
            NotificationRepository notificationRepository,
            EvidenceSanitizer sanitizer,
            OutboundWebhookNotificationClient webhookClient
    ) {
        this.notificationRepository = notificationRepository;
        this.sanitizer = sanitizer;
        this.webhookClient = webhookClient;
    }

    @Transactional
    public NotificationView create(NotificationCommand command) {
        Optional<NotificationEntity> existing = notificationRepository
                .findFirstByCaseIdAndTypeOrderByCreatedAtDesc(command.caseId(), command.type());

        if (existing.isPresent() && 
                existing.get().getCreatedAt().isAfter(Instant.now().minusSeconds(300))) {
            log.debug("Duplicate notification prevented: caseId={}, type={}", 
                    command.caseId(), command.type());
            return toView(existing.get());
        }

        NotificationEntity entity = new NotificationEntity();
        entity.setCaseId(command.caseId());
        entity.setWorkflowRunId(command.workflowRunId());
        entity.setJiraKey(command.jiraKey());
        entity.setType(command.type());
        entity.setTitle(sanitizer.sanitize(command.title()));
        entity.setMessage(command.message() != null ? sanitizer.sanitize(command.message()) : null);
        entity.setSeverity(command.severity());
        entity.setTargetUrl(validateTargetUrl(command.targetUrl()));
        entity.setStatus(NotificationStatus.UNREAD);

        entity = notificationRepository.save(entity);

        log.info("Notification created: id={}, type={}, caseId={}", 
                entity.getId(), entity.getType(), entity.getCaseId());

        webhookClient.sendAsync(entity);

        return toView(entity);
    }

    @Transactional(readOnly = true)
    public List<NotificationView> listUnread(int limit) {
        if (limit > 100) limit = 100;
        List<NotificationEntity> entities = notificationRepository
                .findByStatusOrderByCreatedAtDesc(NotificationStatus.UNREAD, PageRequest.of(0, limit));
        return entities.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public long countUnread() {
        return notificationRepository.countByStatus(NotificationStatus.UNREAD);
    }

    @Transactional
    public NotificationView markRead(UUID notificationId) {
        NotificationEntity entity = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));

        if (entity.getStatus() == NotificationStatus.UNREAD) {
            entity.setStatus(NotificationStatus.READ);
            entity.setReadAt(Instant.now());
            entity = notificationRepository.save(entity);
            log.info("Notification marked as read: id={}", notificationId);
        }

        return toView(entity);
    }

    public void notifyWorkflowCompleted(WorkflowRunEntity workflow, ReplayCaseEntity caseEntity) {
        NotificationType type;
        String severity;
        String title;

        switch (workflow.getStatus()) {
            case SUCCESS -> {
                type = NotificationType.WORKFLOW_SUCCESS;
                severity = "SUCCESS";
                title = "ReplayLab analysis completed successfully";
            }
            case PARTIAL_SUCCESS -> {
                type = NotificationType.WORKFLOW_PARTIAL_SUCCESS;
                severity = "WARNING";
                title = "ReplayLab analysis completed with warnings";
            }
            case FAILED -> {
                type = NotificationType.WORKFLOW_FAILED;
                severity = "ERROR";
                title = "ReplayLab analysis failed";
            }
            default -> {
                log.warn("Unexpected workflow status: {}", workflow.getStatus());
                return;
            }
        }

        String targetUrl = "/replayfix/?caseId=" + caseEntity.getId();

        create(new NotificationCommand(
                caseEntity.getId(),
                workflow.getId(),
                caseEntity.getJiraKey(),
                type,
                title,
                workflow.getSummary(),
                severity,
                targetUrl
        ));
    }

    public void notifyApprovalRequested(ApprovalRequestEntity approval, ReplayCaseEntity caseEntity) {
        String targetUrl = "/replayfix/?caseId=" + caseEntity.getId();

        create(new NotificationCommand(
                caseEntity.getId(),
                null,
                caseEntity.getJiraKey(),
                NotificationType.APPROVAL_REQUESTED,
                "Approval required for " + approval.getTargetType(),
                "Human approval is required to proceed with " + approval.getTargetType(),
                "INFO",
                targetUrl
        ));
    }

    public void notifyJiraCommentPublished(UUID caseId, String jiraKey) {
        String targetUrl = "/replayfix/?caseId=" + caseId;

        create(new NotificationCommand(
                caseId,
                null,
                jiraKey,
                NotificationType.JIRA_COMMENT_PUBLISHED,
                "Jira Evidence Snapshot published",
                "ReplayLab evidence has been published to " + jiraKey,
                "SUCCESS",
                targetUrl
        ));
    }

    private String validateTargetUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("/replayfix/")) return url;
        if (url.startsWith("https://") && url.contains("/browse/")) return url;
        log.warn("Invalid target URL rejected: {}", url);
        return null;
    }

    private NotificationView toView(NotificationEntity entity) {
        return new NotificationView(
                entity.getId(),
                entity.getCaseId(),
                entity.getWorkflowRunId(),
                entity.getJiraKey(),
                entity.getType(),
                entity.getStatus(),
                entity.getTitle(),
                entity.getMessage(),
                entity.getSeverity(),
                entity.getTargetUrl(),
                entity.getCreatedAt(),
                entity.getReadAt()
        );
    }
}
