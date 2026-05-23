package com.campustrade.notification.rabbitmq;

import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.service.NotificationMessageConsumer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("rabbitmq")
public class RabbitNotificationListener {

    private final NotificationMessageConsumer notificationMessageConsumer;

    public RabbitNotificationListener(NotificationMessageConsumer notificationMessageConsumer) {
        this.notificationMessageConsumer = notificationMessageConsumer;
    }

    @RabbitListener(queues = "${app.rabbitmq.notification-queue}")
    public void onMessage(NotificationEventPayload payload) {
        notificationMessageConsumer.consume(payload);
    }
}
