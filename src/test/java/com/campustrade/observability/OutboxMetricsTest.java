package com.campustrade.observability;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.repository.InMemoryNotificationOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxMetricsTest {

    @Test
    void shouldExposeOutboxBacklogGauges() {
        InMemoryNotificationOutboxRepository repository = new InMemoryNotificationOutboxRepository();
        LocalDateTime now = LocalDateTime.now();
        repository.save(event("due", OutboxStatus.PENDING, now.minusSeconds(1)));
        repository.save(event("future", OutboxStatus.PENDING, now.plusMinutes(5)));
        repository.save(event("failed", OutboxStatus.FAILED, null));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(repository).bindTo(registry);

        assertEquals(2.0, registry.get("campustrade.outbox.pending").gauge().value());
        assertEquals(1.0, registry.get("campustrade.outbox.pending.due").gauge().value());
        assertEquals(1.0, registry.get("campustrade.outbox.failed").gauge().value());
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
