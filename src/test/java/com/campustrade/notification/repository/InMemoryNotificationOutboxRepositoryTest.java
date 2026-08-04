package com.campustrade.notification.repository;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryNotificationOutboxRepositoryTest {

    @Test
    void shouldCountOutboxEventsForMetrics() {
        InMemoryNotificationOutboxRepository repository = new InMemoryNotificationOutboxRepository();
        LocalDateTime now = LocalDateTime.now();
        repository.save(event("due", OutboxStatus.PENDING, now.minusSeconds(1)));
        repository.save(event("future", OutboxStatus.PENDING, now.plusMinutes(5)));
        repository.save(event("failed", OutboxStatus.FAILED, null));

        assertEquals(2, repository.countByStatus(OutboxStatus.PENDING));
        assertEquals(1, repository.countPendingDue(now));
        assertEquals(1, repository.countByStatus(OutboxStatus.FAILED));
    }

    private NotificationOutboxEvent event(String eventId, OutboxStatus status, LocalDateTime nextRetryAt) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setEventId(eventId);
        event.setReceiverId(1001L);
        event.setType("ORDER_COMPLETED");
        event.setTitle("Order completed");
        event.setContent("Remember to review");
        event.setRelatedId(3001L);
        event.setStatus(status);
        event.setRetryCount(0);
        event.setNextRetryAt(nextRetryAt);
        return event;
    }
}
