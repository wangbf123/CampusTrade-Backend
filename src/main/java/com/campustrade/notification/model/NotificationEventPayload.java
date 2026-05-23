package com.campustrade.notification.model;

public record NotificationEventPayload(
        String eventId,
        Long receiverId,
        String type,
        String title,
        String content,
        Long relatedId
) {
    public static NotificationEventPayload from(NotificationOutboxEvent event) {
        return new NotificationEventPayload(
                event.getEventId(),
                event.getReceiverId(),
                event.getType(),
                event.getTitle(),
                event.getContent(),
                event.getRelatedId()
        );
    }
}
