package com.campustrade.notification.service;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class NotificationOutboxService {

    private final NotificationOutboxRepository outboxRepository;

    public NotificationOutboxService(NotificationOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public NotificationOutboxEvent enqueue(Long receiverId, String type, String title, String content, Long relatedId) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setReceiverId(receiverId);
        event.setType(type);
        event.setTitle(title);
        event.setContent(content);
        event.setRelatedId(relatedId);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        return outboxRepository.save(event);
    }
}
