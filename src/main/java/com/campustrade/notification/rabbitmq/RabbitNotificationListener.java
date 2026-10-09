package com.campustrade.notification.rabbitmq;

import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.service.NotificationMessageConsumer;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLTransientException;

@Component
@Profile("rabbitmq")
public class RabbitNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationListener.class);
    private final NotificationMessageConsumer consumer;
    private final RabbitNotificationPublisher publisher;
    private final RabbitNotificationProperties properties;
    private final MeterRegistry registry;

    public RabbitNotificationListener(NotificationMessageConsumer consumer, RabbitNotificationPublisher publisher,
                                     RabbitNotificationProperties properties, MeterRegistry registry) {
        this.consumer = consumer;
        this.publisher = publisher;
        this.properties = properties;
        this.registry = registry;
    }

    @RabbitListener(queues = "${app.rabbitmq.notification-queue}")
    public void onMessage(NotificationEventPayload payload, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Timer.Sample sample = Timer.start(registry);
        try {
            consumer.consume(payload); // Transactional proxy returns only after database commit.
            channel.basicAck(tag, false);
            outcome("committed");
        } catch (Exception exception) {
            Object header = message.getMessageProperties().getHeader("notification-attempt");
            int attempt = header instanceof Number number ? Math.max(0, number.intValue()) : 0;
            if (transientFailure(exception) && attempt < Math.max(0, properties.getConsumerMaxRetry())) {
                try {
                    publisher.publishRetry(payload, message, attempt + 1);
                    channel.basicAck(tag, false);
                    outcome("retry_scheduled");
                } catch (RuntimeException retryFailed) {
                    channel.basicNack(tag, false, true); // Keep the original if the retry publish is uncertain.
                    outcome("retry_publish_failed");
                }
            } else {
                channel.basicNack(tag, false, false);
                outcome("dead_lettered");
            }
            log.warn("Notification consume failed for event {} ({})", payload.eventId(), exception.getClass().getSimpleName());
        } finally {
            sample.stop(registry.timer("campustrade.notification.consume.duration"));
        }
    }

    private void outcome(String value) { registry.counter("campustrade.notification.consume", "outcome", value).increment(); }

    private static boolean transientFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientException) { return true; }
        }
        return false;
    }
}
