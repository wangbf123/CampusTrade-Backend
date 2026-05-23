package com.campustrade.notification.service;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.repository.NotificationOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OutboxPublishService {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationPublisher notificationPublisher;
    private final int batchSize;
    private final int maxRetry;

    public OutboxPublishService(
            NotificationOutboxRepository outboxRepository,
            NotificationPublisher notificationPublisher,
            @Value("${app.outbox.batch-size:20}") int batchSize,
            @Value("${app.outbox.max-retry:5}") int maxRetry
    ) {
        this.outboxRepository = outboxRepository;
        this.notificationPublisher = notificationPublisher;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    public int publishPending() {
        List<NotificationOutboxEvent> events = outboxRepository.findPendingDue(LocalDateTime.now(), batchSize);
        int published = 0;
        for (NotificationOutboxEvent event : events) {
            try {
                notificationPublisher.publish(event);
                if (outboxRepository.markPublished(event.getEventId())) {
                    published++;
                }
            } catch (Exception exception) {
                outboxRepository.markFailed(
                        event.getEventId(),
                        exception.getMessage(),
                        LocalDateTime.now().plusSeconds(backoffSeconds(event.getRetryCount())),
                        maxRetry
                );
            }
        }
        return published;
    }

    private long backoffSeconds(int retryCount) {
        return Math.min(60, 2L << Math.min(retryCount, 5));
    }
}
