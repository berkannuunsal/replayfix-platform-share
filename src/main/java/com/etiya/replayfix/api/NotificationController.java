package com.etiya.replayfix.api;

import com.etiya.replayfix.model.NotificationView;
import com.etiya.replayfix.service.ReplayFixNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final ReplayFixNotificationService notificationService;

    public NotificationController(ReplayFixNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationView>> listNotifications(
            @RequestParam(required = false, defaultValue = "UNREAD") String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        if ("UNREAD".equals(status)) {
            List<NotificationView> notifications = notificationService.listUnread(limit);
            return ResponseEntity.ok(notifications);
        }
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        long count = notificationService.countUnread();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationView> markAsRead(@PathVariable UUID id) {
        try {
            NotificationView notification = notificationService.markRead(id);
            return ResponseEntity.ok(notification);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
