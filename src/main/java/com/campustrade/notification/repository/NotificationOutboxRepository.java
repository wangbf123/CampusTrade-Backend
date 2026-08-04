package com.campustrade.notification.repository;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository {

    NotificationOutboxEvent save(NotificationOutboxEvent event);

    Optional<NotificationOutboxEvent> findByEventId(String eventId);

    List<NotificationOutboxEvent> findPendingDue(LocalDateTime now, int limit);

    long countByStatus(OutboxStatus status);

    long countPendingDue(LocalDateTime now);

    boolean markPublished(String eventId);

    boolean markFailed(String eventId, String reason, LocalDateTime nextRetryAt, int maxRetry);
}
