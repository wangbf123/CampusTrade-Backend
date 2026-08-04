package com.campustrade.notification.repository;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryNotificationOutboxRepository implements NotificationOutboxRepository {

    private final AtomicLong idGenerator = new AtomicLong(5000);
    private final Map<String, NotificationOutboxEvent> events = new ConcurrentHashMap<>();

    @Override
    public synchronized NotificationOutboxEvent save(NotificationOutboxEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getId() == null) {
            event.setId(idGenerator.incrementAndGet());
            event.setCreatedAt(now);
        }
        event.setUpdatedAt(now);
        events.put(event.getEventId(), event);
        return event;
    }

    @Override
    public Optional<NotificationOutboxEvent> findByEventId(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    @Override
    public List<NotificationOutboxEvent> findPendingDue(LocalDateTime now, int limit) {
        return events.values().stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .filter(event -> event.getNextRetryAt() == null || !event.getNextRetryAt().isAfter(now))
                .sorted(Comparator.comparing(NotificationOutboxEvent::getCreatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return events.values().stream()
                .filter(event -> event.getStatus() == status)
                .count();
    }

    @Override
    public long countPendingDue(LocalDateTime now) {
        return events.values().stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .filter(event -> event.getNextRetryAt() == null || !event.getNextRetryAt().isAfter(now))
                .count();
    }

    @Override
    public synchronized boolean markPublished(String eventId) {
        NotificationOutboxEvent event = events.get(eventId);
        if (event == null || event.getStatus() != OutboxStatus.PENDING) {
            return false;
        }
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setUpdatedAt(LocalDateTime.now());
        return true;
    }

    @Override
    public synchronized boolean markFailed(String eventId, String reason, LocalDateTime nextRetryAt, int maxRetry) {
        NotificationOutboxEvent event = events.get(eventId);
        if (event == null || event.getStatus() != OutboxStatus.PENDING) {
            return false;
        }
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(reason);
        event.setNextRetryAt(nextRetryAt);
        if (event.getRetryCount() >= maxRetry) {
            event.setStatus(OutboxStatus.FAILED);
        }
        event.setUpdatedAt(LocalDateTime.now());
        return true;
    }
}
