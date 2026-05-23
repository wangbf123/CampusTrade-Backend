package com.campustrade.notification.rabbitmq;

import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.service.NotificationPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
        rabbitTemplate.convertAndSend(
                properties.getNotificationExchange(),
                properties.getNotificationRoutingKey(),
                NotificationEventPayload.from(event)
        );
    }
}
