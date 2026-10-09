package com.campustrade.notification.admin;

import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.notification.model.NotificationEventPayload;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.rabbitmq.RabbitNotificationProperties;
import com.rabbitmq.client.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Profile("rabbitmq")
public class RabbitDeadLetterRecoveryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RabbitTemplate template;
    private final RabbitNotificationProperties properties;
    private final NotificationRecoveryService recovery;

    public RabbitDeadLetterRecoveryService(RabbitTemplate template, RabbitNotificationProperties properties, NotificationRecoveryService recovery) {
        this.template = template;
        this.properties = properties;
        this.recovery = recovery;
    }

    public Optional<DeadLetterHead> peek(AuthenticatedUser admin) {
        NotificationRecoveryService.requireAdmin(admin);
        return template.execute(channel -> {
            GetResponse response = channel.basicGet(properties.getNotificationDeadLetterQueue(), false);
            if (response == null) { return Optional.empty(); }
            try {
                NotificationEventPayload payload = decode(response);
                return Optional.of(new DeadLetterHead(payload, response.getMessageCount() + 1L, null));
            } catch (RuntimeException invalid) {
                return Optional.of(new DeadLetterHead(null, response.getMessageCount() + 1L,
                        "消息格式无效；保留在死信队列，需人工检查"));
            } finally {
                channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
            }
        });
    }

    public NotificationOutboxEvent replay(AuthenticatedUser admin, DeadLetterReplayRequest request) {
        NotificationRecoveryService.requireAdmin(admin);
        return executeRecovery(channel -> {
            GetResponse response = channel.basicGet(properties.getNotificationDeadLetterQueue(), false);
            if (response == null) { throw BizException.notFound("死信队列为空"); }
            boolean committed = false;
            try {
                NotificationEventPayload payload = decode(response);
                if (!payload.eventId().equals(request.eventId())) {
                    throw BizException.conflict("队首事件已变化，请重新查看并选择");
                }
                NotificationOutboxEvent event = recovery.recoverDeadLetter(admin, payload, request.reason());
                committed = true;
                channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                return event;
            } finally {
                // If ACK fails after commit, broker redelivery is safe: the same event_id is preserved.
                if (!committed && channel.isOpen()) {
                    channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
                }
            }
        });
    }

    private <T> T executeRecovery(ChannelCallback<T> action) {
        try { return template.execute(action); }
        catch (AmqpException wrapped) {
            for (Throwable cause = wrapped; cause != null; cause = cause.getCause()) {
                if (cause instanceof BizException business) { throw business; }
            }
            throw wrapped;
        }
    }

    private NotificationEventPayload decode(GetResponse response) {
        // Decode only the expected schema; do not accept a broker-supplied Java type header.
        try {
            NotificationEventPayload payload = JSON.readValue(response.getBody(), NotificationEventPayload.class);
            if (payload.eventId() == null || payload.eventId().isBlank()) { throw BizException.badRequest("通知事件编号缺失"); }
            return payload;
        } catch (java.io.IOException invalid) { throw BizException.badRequest("无法解析通知死信"); }
    }

    public record DeadLetterHead(NotificationEventPayload event, long queueDepth, String error) { }
}
