package com.campustrade.notification.service;

import com.campustrade.message.service.MessageService;
import com.campustrade.notification.model.NotificationEventPayload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationMessageConsumer {

    private final MessageService messageService;

    public NotificationMessageConsumer(MessageService messageService) {
        this.messageService = messageService;
    }

    @Transactional
    public void consume(NotificationEventPayload payload) {
        if (payload == null || invalid(payload.eventId(), 64) || payload.receiverId() == null || payload.receiverId() <= 0
                || invalid(payload.type(), 40) || invalid(payload.title(), 80) || invalid(payload.content(), 500)) {
            throw new IllegalArgumentException("Invalid notification payload");
        }
        messageService.sendIfAbsent(
                payload.eventId(),
                payload.receiverId(),
                payload.type(),
                payload.title(),
                payload.content(),
                payload.relatedId()
        );
    }

    private static boolean invalid(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }
}
