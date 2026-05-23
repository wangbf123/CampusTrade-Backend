package com.campustrade.notification.rabbitmq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitNotificationProperties {

    private String notificationExchange = "campustrade.notification.exchange";
    private String notificationRoutingKey = "notification.created";
    private String notificationQueue = "campustrade.notification.queue";

    public String getNotificationExchange() {
        return notificationExchange;
    }

    public void setNotificationExchange(String notificationExchange) {
        this.notificationExchange = notificationExchange;
    }

    public String getNotificationRoutingKey() {
        return notificationRoutingKey;
    }

    public void setNotificationRoutingKey(String notificationRoutingKey) {
        this.notificationRoutingKey = notificationRoutingKey;
    }

    public String getNotificationQueue() {
        return notificationQueue;
    }

    public void setNotificationQueue(String notificationQueue) {
        this.notificationQueue = notificationQueue;
    }
}
