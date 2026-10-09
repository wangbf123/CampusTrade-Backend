package com.campustrade.order.timeout;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryOrderTimeoutQueueTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 8, 0);
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void shouldClaimOnlyDueOrdersByExpireTimeAndRetainProcessingRecords() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(1L, NOW.plusMinutes(10));
        queue.enqueue(2L, NOW.minusMinutes(1));
        queue.enqueue(3L, NOW.minusMinutes(5));

        List<OrderTimeoutClaim> claims = queue.claimDue(NOW, 10, LEASE);

        assertEquals(List.of(3L, 2L), claims.stream().map(OrderTimeoutClaim::orderId).toList());
        assertEquals(2, queue.snapshot(NOW).processing());
        assertTrue(queue.claimDue(NOW, 10, LEASE).isEmpty());
    }

    @Test
    void shouldRecoverWorkerCrashAndFenceStaleAckAndRetry() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(1L, NOW.minusMinutes(1));
        OrderTimeoutClaim abandoned = queue.claimDue(NOW, 1, LEASE).getFirst();

        assertEquals(0, queue.recoverExpired(NOW.plusSeconds(29), 10));
        assertEquals(1, queue.recoverExpired(NOW.plusSeconds(30), 10));
        OrderTimeoutClaim replacement = queue.claimDue(NOW.plusSeconds(30), 1, LEASE).getFirst();
        assertNotEquals(abandoned.token(), replacement.token());
        assertFalse(queue.ack(abandoned));
        assertFalse(queue.retry(abandoned, NOW.plusHours(1)));
        assertEquals(1, queue.snapshot(NOW).processing());
        assertTrue(queue.ack(replacement));
        assertFalse(queue.ack(replacement));
        assertEquals(0, queue.snapshot(NOW).processing());
    }

    @Test
    void shouldNotOverwriteClaimsOrRetryBackoffDuringDatabaseCompensation() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        assertTrue(queue.enqueueIfMissing(1L, NOW));
        OrderTimeoutClaim claim = queue.claimDue(NOW, 1, LEASE).getFirst();
        assertFalse(queue.enqueueIfMissing(1L, NOW.minusDays(1)));
        assertTrue(queue.retry(claim, NOW.plusMinutes(1)));
        assertFalse(queue.enqueueIfMissing(1L, NOW.minusDays(1)));
        assertTrue(queue.claimDue(NOW, 1, LEASE).isEmpty());
        assertEquals(1, queue.claimDue(NOW.plusMinutes(1), 1, LEASE).size());
    }

    @Test
    void shouldRemovePendingAndProcessingWhenUserHandlesOrder() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(1L, NOW);
        OrderTimeoutClaim claim = queue.claimDue(NOW, 1, LEASE).getFirst();
        queue.remove(1L);
        assertFalse(queue.ack(claim));
        assertEquals(0, queue.recoverExpired(NOW.plusMinutes(1), 10));
        assertTrue(queue.claimDue(NOW.plusMinutes(1), 10, LEASE).isEmpty());
    }

    @Test
    void shouldHaveAtMostOneActiveClaimAcrossConcurrentWorkers() throws Exception {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(1L, NOW);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return queue.claimDue(NOW, 1, LEASE); });
            var second = executor.submit(() -> { start.await(); return queue.claimDue(NOW, 1, LEASE); });
            start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS).size() + second.get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void shouldBoundRecoveryAndRejectInvalidLease() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(1L, NOW);
        queue.enqueue(2L, NOW);
        assertTrue(queue.claimDue(NOW, 0, LEASE).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> queue.claimDue(NOW, 1, Duration.ZERO));
        queue.claimDue(NOW, 2, LEASE);
        assertEquals(1, queue.recoverExpired(NOW.plusSeconds(30), 1));
        assertEquals(1, queue.snapshot(NOW.plusSeconds(30)).expiredLeases());
    }
}
