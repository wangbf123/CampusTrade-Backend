package com.campustrade.notification.service;

import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.model.NotificationOutboxEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!rabbitmq")
public class LocalNotificationPublisher implements NotificationPublisher {

    private final NotificationMessageConsumer notificationMessageConsumer;

    public LocalNotificationPublisher(NotificationMessageConsumer notificationMessageConsumer) {
        this.notificationMessageConsumer = notificationMessageConsumer;
    }

    @Override
    public void publish(NotificationOutboxEvent event) {
        notificationMessageConsumer.consume(NotificationEventPayload.from(event));
    }
}
