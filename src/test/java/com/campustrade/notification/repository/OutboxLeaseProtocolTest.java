package com.campustrade.notification.repository;

import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.service.NotificationOutboxService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class OutboxLeaseProtocolTest {
    @Test
    void concurrentPublishersClaimDisjointSnapshots() throws Exception {
        InMemoryNotificationOutboxRepository repository = new InMemoryNotificationOutboxRepository();
        NotificationOutboxService enqueue = new NotificationOutboxService(repository);
        for (int i = 0; i < 60; i++) { enqueue.enqueue(1L, "TEST", "title", "content", null); }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> repository.claimDue(LocalDateTime.now(), 40, Duration.ofSeconds(30)));
            var second = pool.submit(() -> repository.claimDue(LocalDateTime.now(), 40, Duration.ofSeconds(30)));
            List<NotificationOutboxEvent> a = first.get();
            List<NotificationOutboxEvent> b = second.get();
            assertEquals(60, a.size() + b.size());
            assertTrue(a.stream().noneMatch(event -> b.stream().anyMatch(other -> other.getEventId().equals(event.getEventId()))));
        }
    }

    @Test
    void expiredOwnerCannotAcknowledgeReclaimedTask() {
        InMemoryNotificationOutboxRepository repository = new InMemoryNotificationOutboxRepository();
        var original = new NotificationOutboxService(repository).enqueue(1L, "TEST", "title", "content", null);
        var first = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(1)).getFirst();
        var second = repository.claimDue(LocalDateTime.now().plusSeconds(2), 1, Duration.ofSeconds(30)).getFirst();
        assertNotEquals(first.getClaimToken(), second.getClaimToken());
        assertFalse(repository.markPublished(original.getEventId(), first.getClaimToken()));
        assertFalse(repository.markFailed(original.getEventId(), first.getClaimToken(), "error", 1, 5));
        assertTrue(repository.markPublished(original.getEventId(), second.getClaimToken()));
    }

    @Test
    void retryLimitAndReplayPreserveEventIdentity() {
        InMemoryNotificationOutboxRepository repository = new InMemoryNotificationOutboxRepository();
        var original = new NotificationOutboxService(repository).enqueue(1L, "TEST", "title", "content", null);
        var claim = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(30)).getFirst();
        assertTrue(repository.markFailed(original.getEventId(), claim.getClaimToken(), "error", 1, 1));
        assertEquals(OutboxStatus.FAILED, repository.findByEventId(original.getEventId()).orElseThrow().getStatus());
        assertTrue(repository.replay(original.getEventId(), false));
        var latest = repository.findByEventId(original.getEventId()).orElseThrow();
        assertEquals(original.getId(), latest.getId());
        assertEquals(0, latest.getRetryCount());
        assertEquals(1, latest.getReplayCount());
        assertFalse(repository.replay(original.getEventId(), false));
    }
}
