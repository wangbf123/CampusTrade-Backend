package com.campustrade.notification.repository;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository {

    NotificationOutboxEvent save(NotificationOutboxEvent event);

    Optional<NotificationOutboxEvent> findByEventId(String eventId);

    List<NotificationOutboxEvent> findPendingDue(LocalDateTime now, int limit);

    long countByStatus(OutboxStatus status);

    long countPendingDue(LocalDateTime now);

    List<NotificationOutboxEvent> claimDue(LocalDateTime now, int limit, Duration lease);

    boolean renewLease(String eventId, String claimToken, Duration lease);

    boolean markPublished(String eventId, String claimToken);

    boolean markFailed(String eventId, String claimToken, String reason, long retryDelaySeconds, int maxRetry);

    boolean replay(String eventId, boolean includePublished);

    List<NotificationOutboxEvent> findByStatus(OutboxStatus status, int limit);

    long countExpiredLeases(LocalDateTime now);

    long oldestUnpublishedAgeSeconds(LocalDateTime now);
}
