package com.campustrade.notification.rabbitmq;

import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.service.NotificationPublisher;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Profile("rabbitmq")
public class RabbitNotificationPublisher implements NotificationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitNotificationProperties properties;

    public RabbitNotificationPublisher(RabbitTemplate rabbitTemplate, RabbitNotificationProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(NotificationOutboxEvent event) {
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                properties.getNotificationExchange(),
                properties.getNotificationRoutingKey(),
                NotificationEventPayload.from(event),
                message -> {
                    message.getMessageProperties().setMessageId(event.getEventId());
                    return message;
                },
                correlationData
        );

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(properties.getPublisherConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ publish confirm failed: "
                        + (confirm == null ? "confirm is null" : confirm.getReason()));
            }

            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new IllegalStateException("RabbitMQ returned message: " + returned.getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting RabbitMQ confirm", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("RabbitMQ publish failed for event " + event.getEventId(), exception);
        }
    }
}
