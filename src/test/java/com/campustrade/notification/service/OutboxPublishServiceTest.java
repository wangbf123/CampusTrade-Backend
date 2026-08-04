package com.campustrade.notification.service;

import com.campustrade.message.repository.InMemoryMessageRepository;
import com.campustrade.message.service.MessageService;
import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.repository.InMemoryNotificationOutboxRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxPublishServiceTest {

    @Test
    void shouldPublishPendingOutboxEventAndCreateMessage() {
        InMemoryMessageRepository messageRepository = new InMemoryMessageRepository();
        MessageService messageService = new MessageService(messageRepository);
        NotificationMessageConsumer consumer = new NotificationMessageConsumer(messageService);
        InMemoryNotificationOutboxRepository outboxRepository = new InMemoryNotificationOutboxRepository();
        NotificationOutboxService outboxService = new NotificationOutboxService(outboxRepository);
        OutboxPublishService publishService = new OutboxPublishService(
                outboxRepository,
                new LocalNotificationPublisher(consumer),
                20,
                5
        );

        NotificationOutboxEvent event = outboxService.enqueue(1001L, "ORDER_COMPLETED", "Order completed", "Remember to review", 3001L);

        int published = publishService.publishPending();

        assertEquals(1, published);
        assertEquals(OutboxStatus.PUBLISHED, outboxRepository.findByEventId(event.getEventId()).orElseThrow().getStatus());
        assertEquals(1, messageRepository.findByReceiverId(1001L).size());
    }

    @Test
    void shouldMarkEventForRetryWhenPublishFails() {
        InMemoryNotificationOutboxRepository outboxRepository = new InMemoryNotificationOutboxRepository();
        NotificationOutboxService outboxService = new NotificationOutboxService(outboxRepository);
        OutboxPublishService publishService = new OutboxPublishService(
                outboxRepository,
                event -> {
                    throw new IllegalStateException("mq unavailable");
                },
                20,
                5
        );

        NotificationOutboxEvent event = outboxService.enqueue(1001L, "ORDER_COMPLETED", "Order completed", "Remember to review", 3001L);

        int published = publishService.publishPending();

        assertEquals(0, published);
        NotificationOutboxEvent latest = outboxRepository.findByEventId(event.getEventId()).orElseThrow();
        assertEquals(OutboxStatus.PENDING, latest.getStatus());
        assertEquals(1, latest.getRetryCount());
    }

    @Test
    void shouldConsumeNotificationIdempotentlyByEventId() {
        InMemoryMessageRepository messageRepository = new InMemoryMessageRepository();
        MessageService messageService = new MessageService(messageRepository);
        NotificationMessageConsumer consumer = new NotificationMessageConsumer(messageService);
        NotificationEventPayload payload = new NotificationEventPayload(
                "event-1",
                1001L,
                "ORDER_CANCELLED",
                "Appointment cancelled",
                "Order has been cancelled",
                3001L
        );

        consumer.consume(payload);
        consumer.consume(payload);

        assertEquals(1, messageRepository.findByReceiverId(1001L).size());
    }
}
