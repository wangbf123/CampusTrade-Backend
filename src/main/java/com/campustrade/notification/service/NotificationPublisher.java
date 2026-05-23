package com.campustrade.notification.service;

import com.campustrade.notification.model.NotificationOutboxEvent;

public interface NotificationPublisher {

    void publish(NotificationOutboxEvent event);
}
