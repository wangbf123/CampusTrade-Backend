package com.campustrade.notification.rabbitmq;

import com.campustrade.notification.model.NotificationOutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class RabbitNotificationPublisherTest {

    @Test
    void shouldWaitForBrokerConfirmBeforeReturning() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitNotificationProperties properties = new RabbitNotificationProperties();
        properties.setPublisherConfirmTimeoutMs(1000);
        RabbitNotificationPublisher publisher = new RabbitNotificationPublisher(rabbitTemplate, properties);

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(properties.getNotificationExchange()),
                eq(properties.getNotificationRoutingKey()),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertDoesNotThrow(() -> publisher.publish(newEvent("event-1")));
    }

    @Test
    void shouldFailWhenMessageIsReturnedByBroker() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitNotificationProperties properties = new RabbitNotificationProperties();
        properties.setPublisherConfirmTimeoutMs(1000);
        RabbitNotificationPublisher publisher = new RabbitNotificationPublisher(rabbitTemplate, properties);

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.setReturned(new ReturnedMessage(
                    new Message(new byte[0], new MessageProperties()),
                    312,
                    "NO_ROUTE",
                    properties.getNotificationExchange(),
                    properties.getNotificationRoutingKey()
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(properties.getNotificationExchange()),
                eq(properties.getNotificationRoutingKey()),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertThrows(IllegalStateException.class, () -> publisher.publish(newEvent("event-2")));
    }

    private NotificationOutboxEvent newEvent(String eventId) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setEventId(eventId);
        event.setReceiverId(1001L);
        event.setType("ORDER_COMPLETED");
        event.setTitle("Order completed");
        event.setContent("Remember to review");
        event.setRelatedId(3001L);
        return event;
    }
}
