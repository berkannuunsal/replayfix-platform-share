package com.etiya.replaylab.service;

import com.etiya.replaylab.domain.NotificationEntity;
import com.etiya.replaylab.domain.NotificationStatus;
import com.etiya.replaylab.domain.NotificationType;
import com.etiya.replaylab.event.AiAnalysisCompletedEvent;
import com.etiya.replaylab.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiNotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private AiNotificationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AiNotificationService(notificationRepository);
    }

    @Test
    void testHandleAiAnalysisCompleted_CreatesNotificationWithCorrectType() {
        UUID caseId = UUID.randomUUID();
        AiAnalysisCompletedEvent event = new AiAnalysisCompletedEvent(
                caseId,
                "TEST-123",
                NotificationType.AI_ANALYSIS_COMPLETED,
                "AI analysis completed",
                "Synthetic analysis available for review. Confidence: 72%",
                "INFO",
                true
        );

        service.handleAiAnalysisCompleted(event);

        ArgumentCaptor<NotificationEntity> captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(captor.capture());

        NotificationEntity notification = captor.getValue();
        assertThat(notification.getCaseId()).isEqualTo(caseId);
        assertThat(notification.getJiraKey()).isEqualTo("TEST-123");
        assertThat(notification.getType()).isEqualTo(NotificationType.AI_ANALYSIS_COMPLETED);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.UNREAD);
        assertThat(notification.getTitle()).isEqualTo("AI analysis completed");
        assertThat(notification.getMessage()).contains("Synthetic analysis");
        assertThat(notification.getSeverity()).isEqualTo("INFO");
        assertThat(notification.getTargetUrl()).contains(caseId.toString());
    }

    @Test
    void testHandleAiAnalysisCompleted_FailureDoesNotThrow() {
        UUID caseId = UUID.randomUUID();
        AiAnalysisCompletedEvent event = new AiAnalysisCompletedEvent(
                caseId,
                "TEST-456",
                NotificationType.AI_ANALYSIS_FAILED,
                "AI analysis failed",
                "Provider unavailable",
                "ERROR",
                false
        );

        when(notificationRepository.save(any(NotificationEntity.class)))
                .thenThrow(new RuntimeException("Database constraint violation"));

        // Should not throw - notification failure is non-critical
        service.handleAiAnalysisCompleted(event);

        verify(notificationRepository).save(any(NotificationEntity.class));
    }

    @Test
    void testHandleAiAnalysisCompleted_AcceptsAiAnalysisCompletedType() {
        UUID caseId = UUID.randomUUID();
        AiAnalysisCompletedEvent event = new AiAnalysisCompletedEvent(
                caseId,
                "TEST-789",
                NotificationType.AI_ANALYSIS_COMPLETED, // This should be accepted by constraint
                "AI analysis completed",
                "AI analysis available",
                "SUCCESS",
                false
        );

        service.handleAiAnalysisCompleted(event);

        ArgumentCaptor<NotificationEntity> captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(captor.capture());

        NotificationEntity notification = captor.getValue();
        assertThat(notification.getType()).isEqualTo(NotificationType.AI_ANALYSIS_COMPLETED);
    }
}
