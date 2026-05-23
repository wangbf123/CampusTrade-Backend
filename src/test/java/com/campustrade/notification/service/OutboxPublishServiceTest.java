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

        NotificationOutboxEvent event = outboxService.enqueue(1001L, "ORDER_COMPLETED", "交易已完成", "记得评价", 3001L);

        int published = publishService.publishPending();

        assertEquals(1, published);
        assertEquals(OutboxStatus.PUBLISHED, outboxRepository.findByEventId(event.getEventId()).orElseThrow().getStatus());
        assertEquals(1, messageRepository.findByReceiverId(1001L).size());
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
                "预约已取消",
                "订单已取消",
                3001L
        );

        consumer.consume(payload);
        consumer.consume(payload);

        assertEquals(1, messageRepository.findByReceiverId(1001L).size());
    }
}
