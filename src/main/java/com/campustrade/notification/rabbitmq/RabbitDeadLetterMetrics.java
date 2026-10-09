package com.campustrade.notification.rabbitmq;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Properties;

/** Scrapes read cached observations; an unavailable broker does not block the metrics endpoint. */
@Component
@Profile("rabbitmq")
public class RabbitDeadLetterMetrics {
    private final RabbitAdmin admin;
    private final RabbitNotificationProperties properties;
    private volatile long depth = -1;
    private volatile long observedAtEpochSeconds;

    public RabbitDeadLetterMetrics(ConnectionFactory factory, RabbitNotificationProperties properties, MeterRegistry registry) {
        this.admin = new RabbitAdmin(factory);
        this.properties = properties;
        Gauge.builder("campustrade.notification.dlq.depth", this, value -> value.depth)
                .description("Last observed DLQ depth; -1 means unknown").register(registry);
        Gauge.builder("campustrade.notification.dlq.observed.at", this, value -> value.observedAtEpochSeconds)
                .description("Epoch seconds of last successful DLQ observation").register(registry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.dlq-observe-delay:30000}")
    public void observe() {
        try {
            Properties state = admin.getQueueProperties(properties.getNotificationDeadLetterQueue());
            if (state != null) {
                depth = ((Number) state.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
                observedAtEpochSeconds = System.currentTimeMillis() / 1000;
            } else { depth = -1; }
        } catch (RuntimeException failure) { depth = -1; }
    }
}
