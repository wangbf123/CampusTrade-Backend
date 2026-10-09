package com.campustrade.notification.repository;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Development mode only: leases are atomic within this JVM, but events do not survive a restart. */
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
        events.put(event.getEventId(), copy(event));
        return event;
    }

    @Override
    public synchronized Optional<NotificationOutboxEvent> findByEventId(String eventId) {
        return Optional.ofNullable(events.get(eventId)).map(InMemoryNotificationOutboxRepository::copy);
    }

    @Override
    public synchronized List<NotificationOutboxEvent> findPendingDue(LocalDateTime now, int limit) {
        return events.values().stream().filter(event -> due(event, now))
                .sorted(Comparator.comparing(NotificationOutboxEvent::getCreatedAt))
                .limit(Math.max(1, limit)).map(InMemoryNotificationOutboxRepository::copy).toList();
    }

    @Override
    public synchronized long countByStatus(OutboxStatus status) {
        return events.values().stream().filter(event -> event.getStatus() == status).count();
    }

    @Override
    public synchronized long countPendingDue(LocalDateTime now) {
        return events.values().stream().filter(event -> due(event, now)).count();
    }

    @Override
    public synchronized List<NotificationOutboxEvent> claimDue(LocalDateTime now, int limit, Duration lease) {
        return events.values().stream().filter(event -> due(event, now) || expired(event, now))
                .sorted(Comparator.comparing(NotificationOutboxEvent::getCreatedAt).thenComparing(NotificationOutboxEvent::getId))
                .limit(Math.min(100, Math.max(1, limit)))
                .map(event -> {
                    event.setStatus(OutboxStatus.PROCESSING);
                    event.setClaimToken(UUID.randomUUID().toString());
                    event.setLeaseUntil(now.plusSeconds(Math.max(1, lease.toSeconds())));
                    event.setUpdatedAt(now);
                    return copy(event);
                }).toList();
    }

    @Override
    public synchronized boolean renewLease(String eventId, String claimToken, Duration lease) {
        NotificationOutboxEvent event = owned(eventId, claimToken);
        if (event == null) { return false; }
        event.setLeaseUntil(LocalDateTime.now().plusSeconds(Math.max(1, lease.toSeconds())));
        return true;
    }

    @Override
    public synchronized boolean markPublished(String eventId, String claimToken) {
        NotificationOutboxEvent event = owned(eventId, claimToken);
        if (event == null) { return false; }
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setLastError(null);
        event.setNextRetryAt(null);
        release(event);
        return true;
    }

    @Override
    public synchronized boolean markFailed(String eventId, String claimToken, String reason, long retryDelaySeconds, int maxRetry) {
        NotificationOutboxEvent event = owned(eventId, claimToken);
        if (event == null) { return false; }
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(reason == null ? "Publish failed" : reason.substring(0, Math.min(500, reason.length())));
        event.setNextRetryAt(LocalDateTime.now().plusSeconds(Math.max(1, retryDelaySeconds)));
        event.setStatus(event.getRetryCount() >= Math.max(1, maxRetry) ? OutboxStatus.FAILED : OutboxStatus.PENDING);
        release(event);
        return true;
    }

    @Override
    public synchronized boolean replay(String eventId, boolean includePublished) {
        NotificationOutboxEvent event = events.get(eventId);
        if (event == null || (event.getStatus() != OutboxStatus.FAILED && (!includePublished || event.getStatus() != OutboxStatus.PUBLISHED))) {
            return false;
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setReplayCount(event.getReplayCount() + 1);
        event.setLastError(null);
        event.setNextRetryAt(LocalDateTime.now());
        release(event);
        return true;
    }

    @Override
    public synchronized List<NotificationOutboxEvent> findByStatus(OutboxStatus status, int limit) {
        return events.values().stream().filter(event -> event.getStatus() == status)
                .sorted(Comparator.comparing(NotificationOutboxEvent::getCreatedAt).thenComparing(NotificationOutboxEvent::getId))
                .limit(Math.min(100, Math.max(1, limit))).map(InMemoryNotificationOutboxRepository::copy).toList();
    }

    @Override
    public synchronized long countExpiredLeases(LocalDateTime now) {
        return events.values().stream().filter(event -> expired(event, now)).count();
    }

    @Override
    public synchronized long oldestUnpublishedAgeSeconds(LocalDateTime now) {
        return events.values().stream().filter(event -> event.getStatus() == OutboxStatus.PENDING || event.getStatus() == OutboxStatus.PROCESSING)
                .map(NotificationOutboxEvent::getCreatedAt).min(LocalDateTime::compareTo)
                .map(created -> Math.max(0, Duration.between(created, now).toSeconds())).orElse(0L);
    }

    private NotificationOutboxEvent owned(String eventId, String token) {
        NotificationOutboxEvent event = events.get(eventId);
        return event != null && token != null && event.getStatus() == OutboxStatus.PROCESSING
                && Objects.equals(token, event.getClaimToken()) && event.getLeaseUntil().isAfter(LocalDateTime.now()) ? event : null;
    }

    private static boolean due(NotificationOutboxEvent event, LocalDateTime now) {
        return event.getStatus() == OutboxStatus.PENDING && (event.getNextRetryAt() == null || !event.getNextRetryAt().isAfter(now));
    }

    private static boolean expired(NotificationOutboxEvent event, LocalDateTime now) {
        return event.getStatus() == OutboxStatus.PROCESSING && event.getLeaseUntil() != null && !event.getLeaseUntil().isAfter(now);
    }

    private static void release(NotificationOutboxEvent event) {
        event.setClaimToken(null);
        event.setLeaseUntil(null);
        event.setUpdatedAt(LocalDateTime.now());
    }

    private static NotificationOutboxEvent copy(NotificationOutboxEvent from) {
        NotificationOutboxEvent to = new NotificationOutboxEvent();
        to.setId(from.getId()); to.setEventId(from.getEventId()); to.setReceiverId(from.getReceiverId());
        to.setType(from.getType()); to.setTitle(from.getTitle()); to.setContent(from.getContent()); to.setRelatedId(from.getRelatedId());
        to.setStatus(from.getStatus()); to.setRetryCount(from.getRetryCount()); to.setNextRetryAt(from.getNextRetryAt());
        to.setLastError(from.getLastError()); to.setClaimToken(from.getClaimToken()); to.setLeaseUntil(from.getLeaseUntil());
        to.setReplayCount(from.getReplayCount()); to.setCreatedAt(from.getCreatedAt()); to.setUpdatedAt(from.getUpdatedAt());
        return to;
    }
}
