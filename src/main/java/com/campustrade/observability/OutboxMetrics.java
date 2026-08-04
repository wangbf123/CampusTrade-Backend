package com.campustrade.observability;

import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OutboxMetrics implements MeterBinder {

    private final NotificationOutboxRepository outboxRepository;

    public OutboxMetrics(NotificationOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("campustrade.outbox.pending", outboxRepository,
                        repository -> repository.countByStatus(OutboxStatus.PENDING))
                .description("Total pending notification outbox events")
                .register(registry);
        Gauge.builder("campustrade.outbox.pending.due", outboxRepository,
                        repository -> repository.countPendingDue(LocalDateTime.now()))
                .description("Pending notification outbox events ready for publishing")
                .register(registry);
        Gauge.builder("campustrade.outbox.failed", outboxRepository,
                        repository -> repository.countByStatus(OutboxStatus.FAILED))
                .description("Failed notification outbox events")
                .register(registry);
    }
}
