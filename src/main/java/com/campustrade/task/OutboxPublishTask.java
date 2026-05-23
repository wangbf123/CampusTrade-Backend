package com.campustrade.task;

import com.campustrade.notification.service.OutboxPublishService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublishTask {

    private final OutboxPublishService outboxPublishService;

    public OutboxPublishTask(OutboxPublishService outboxPublishService) {
        this.outboxPublishService = outboxPublishService;
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay:3000}")
    public void publishPending() {
        outboxPublishService.publishPending();
    }
}
