package com.campustrade.notification.service;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OutboxPublishService {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublishService.class);
    private final NotificationOutboxRepository outboxRepository;
    private final NotificationPublisher notificationPublisher;
    private final int batchSize;
    private final int maxRetry;
    private final Duration lease;
    private final MeterRegistry registry;

    public OutboxPublishService(NotificationOutboxRepository repository, NotificationPublisher publisher, int batchSize, int maxRetry) {
        this(repository, publisher, batchSize, maxRetry, 30, new SimpleMeterRegistry());
    }

    @Autowired
    public OutboxPublishService(NotificationOutboxRepository repository, NotificationPublisher publisher,
            @Value("${app.outbox.batch-size:20}") int batchSize,
            @Value("${app.outbox.max-retry:5}") int maxRetry,
            @Value("${app.outbox.lease-seconds:30}") long leaseSeconds, MeterRegistry registry) {
        this.outboxRepository = repository;
        this.notificationPublisher = publisher;
        this.batchSize = Math.min(100, Math.max(1, batchSize));
        this.maxRetry = Math.max(1, maxRetry);
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.registry = registry;
    }

    public int publishPending() {
        List<NotificationOutboxEvent> events = outboxRepository.claimDue(LocalDateTime.now(), batchSize, lease);
        int published = 0;
        for (NotificationOutboxEvent event : events) {
            // A batch can take longer than its lease. Never send an event already reclaimed by another instance.
            if (!outboxRepository.renewLease(event.getEventId(), event.getClaimToken(), lease)) {
                outcome("lease_lost");
                continue;
            }
            Timer.Sample sample = Timer.start(registry);
            try {
                notificationPublisher.publish(event);
                if (outboxRepository.markPublished(event.getEventId(), event.getClaimToken())) {
                    published++;
                    outcome("confirmed");
                } else {
                    // The broker may have received it. Reclaim/retry intentionally uses the same event_id.
                    outcome("lease_lost");
                }
            } catch (Exception exception) {
                outboxRepository.markFailed(event.getEventId(), event.getClaimToken(), safeError(exception),
                        backoffSeconds(event.getRetryCount()), maxRetry);
                outcome("failed");
                log.warn("Notification publish failed for event {} ({})", event.getEventId(), exception.getClass().getSimpleName());
            } finally {
                sample.stop(registry.timer("campustrade.outbox.publish.duration"));
            }
        }
        return published;
    }

    private void outcome(String value) { registry.counter("campustrade.outbox.publish", "outcome", value).increment(); }

    static long backoffSeconds(int retryCount) {
        long ceiling = Math.min(60, 2L << Math.min(Math.max(0, retryCount), 5));
        return ThreadLocalRandom.current().nextLong(Math.max(1, ceiling / 2), ceiling + 1);
    }

    private static String safeError(Exception exception) {
        // Store only a classification: connection/URI messages can include credentials or user data.
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) { root = root.getCause(); }
        return exception.getClass().getSimpleName() + ": " + root.getClass().getSimpleName();
    }
}
