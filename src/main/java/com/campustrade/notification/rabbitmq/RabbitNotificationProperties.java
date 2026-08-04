package com.campustrade.notification.rabbitmq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitNotificationProperties {

    private String notificationExchange = "campustrade.notification.exchange";
    private String notificationRoutingKey = "notification.created";
    private String notificationQueue = "campustrade.notification.queue.v2";
    private String notificationDeadLetterExchange = "campustrade.notification.dlx";
    private String notificationDeadLetterRoutingKey = "notification.dead";
    private String notificationDeadLetterQueue = "campustrade.notification.dlq.v2";
    private long publisherConfirmTimeoutMs = 5000;

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

    public String getNotificationDeadLetterExchange() {
        return notificationDeadLetterExchange;
    }

    public void setNotificationDeadLetterExchange(String notificationDeadLetterExchange) {
        this.notificationDeadLetterExchange = notificationDeadLetterExchange;
    }

    public String getNotificationDeadLetterRoutingKey() {
        return notificationDeadLetterRoutingKey;
    }

    public void setNotificationDeadLetterRoutingKey(String notificationDeadLetterRoutingKey) {
        this.notificationDeadLetterRoutingKey = notificationDeadLetterRoutingKey;
    }

    public String getNotificationDeadLetterQueue() {
        return notificationDeadLetterQueue;
    }

    public void setNotificationDeadLetterQueue(String notificationDeadLetterQueue) {
        this.notificationDeadLetterQueue = notificationDeadLetterQueue;
    }

    public long getPublisherConfirmTimeoutMs() {
        return publisherConfirmTimeoutMs;
    }

    public void setPublisherConfirmTimeoutMs(long publisherConfirmTimeoutMs) {
        this.publisherConfirmTimeoutMs = publisherConfirmTimeoutMs;
    }
}
