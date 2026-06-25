package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.NotificationEntity;
import com.etiya.replaylab.domain.NotificationStatus;
import com.etiya.replaylab.event.AiAnalysisCompletedEvent;
import com.etiya.replaylab.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class AiNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AiNotificationService.class);

    private final NotificationRepository notificationRepository;

    public AiNotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAiAnalysisCompleted(AiAnalysisCompletedEvent event) {
        log.info("AI_ANALYSIS_STAGE=AI_NOTIFICATION_CREATION: caseId={}, type={}", 
                event.caseId(), event.notificationType());
        
        try {
            NotificationEntity notification = new NotificationEntity();
            notification.setCaseId(event.caseId());
            notification.setJiraKey(event.jiraKey());
            notification.setType(event.notificationType());
            notification.setStatus(NotificationStatus.UNREAD);
            notification.setTitle(event.title());
            notification.setMessage(event.message());
            notification.setSeverity(event.severity());
            notification.setTargetUrl("/replaylab/?caseId=" + event.caseId());
            
            notificationRepository.save(notification);
            
            log.info("AI_ANALYSIS_STAGE=AI_NOTIFICATION_CREATED: caseId={}, type={}", 
                    event.caseId(), event.notificationType());
        } catch (Exception e) {
            log.warn("AI_ANALYSIS_STAGE=AI_NOTIFICATION_FAILED_NON_CRITICAL: caseId={}, type={}, error={}", 
                    event.caseId(), event.notificationType(), e.getMessage(), e);
        }
    }
}
