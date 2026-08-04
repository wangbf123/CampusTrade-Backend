package com.campustrade.notification.rabbitmq;

import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.service.NotificationMessageConsumer;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Profile("rabbitmq")
public class RabbitNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationListener.class);

    private final NotificationMessageConsumer notificationMessageConsumer;

    public RabbitNotificationListener(NotificationMessageConsumer notificationMessageConsumer) {
        this.notificationMessageConsumer = notificationMessageConsumer;
    }

    @RabbitListener(queues = "${app.rabbitmq.notification-queue}")
    public void onMessage(NotificationEventPayload payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            notificationMessageConsumer.consume(payload);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
            log.error("Failed to consume notification event {}", payload.eventId(), exception);
        }
    }
}
