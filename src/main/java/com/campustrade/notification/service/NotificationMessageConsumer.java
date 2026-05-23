package com.campustrade.notification.service;

import com.campustrade.message.service.MessageService;
import com.campustrade.notification.model.NotificationEventPayload;
import org.springframework.stereotype.Service;

@Service
public class NotificationMessageConsumer {

    private final MessageService messageService;

    public NotificationMessageConsumer(MessageService messageService) {
        this.messageService = messageService;
    }

    public void consume(NotificationEventPayload payload) {
        messageService.sendIfAbsent(
                payload.eventId(),
                payload.receiverId(),
                payload.type(),
                payload.title(),
                payload.content(),
                payload.relatedId()
        );
    }
}
