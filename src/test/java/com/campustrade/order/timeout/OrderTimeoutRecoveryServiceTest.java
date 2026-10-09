package com.campustrade.order.timeout;

import com.campustrade.observability.OrderTimeoutMetrics;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.order.repository.InMemoryTradeOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class OrderTimeoutRecoveryServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 8, 0);

    @Test
    void shouldRebuildLostQueueFromDatabaseAndIncludeUpcomingDeadlines() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        TradeOrder due = order(repository, NOW.minusMinutes(5), OrderStatus.PENDING);
        TradeOrder upcoming = order(repository, NOW.plusSeconds(30), OrderStatus.PENDING);
        order(repository, NOW.plusHours(1), OrderStatus.PENDING);
        order(repository, NOW.minusMinutes(5), OrderStatus.CANCELLED);
        InMemoryOrderTimeoutQueue emptyQueue = new InMemoryOrderTimeoutQueue();
        OrderTimeoutMetrics metrics = new OrderTimeoutMetrics();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        OrderTimeoutRecoveryService recovery = new OrderTimeoutRecoveryService(repository, emptyQueue, metrics, 10, 60);

        assertEquals(2, recovery.recoverAndReconcile(NOW));
        assertEquals(0, recovery.recoverAndReconcile(NOW));
        assertEquals(due.getId(), emptyQueue.claimDue(NOW, 10, Duration.ofMinutes(1)).getFirst().orderId());
        assertEquals(upcoming.getId(), emptyQueue.claimDue(NOW.plusSeconds(30), 10, Duration.ofMinutes(1)).getFirst().orderId());
        assertEquals(2, registry.get("campustrade.order.timeout.compensated").functionCounter().count());
    }

    @Test
    void shouldNotStarveLaterOrdersWhenEarlierTasksRemainInProcessing() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        TradeOrder first = order(repository, NOW.minusMinutes(10), OrderStatus.PENDING);
        TradeOrder second = order(repository, NOW.minusMinutes(10), OrderStatus.PENDING);
        TradeOrder last = order(repository, NOW.minusMinutes(5), OrderStatus.PENDING);
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(first.getId(), first.getExpireAt());
        queue.enqueue(second.getId(), second.getExpireAt());
        queue.claimDue(NOW, 2, Duration.ofHours(1));
        OrderTimeoutRecoveryService recovery = new OrderTimeoutRecoveryService(repository, queue,
                new OrderTimeoutMetrics(), 2, 0);

        assertEquals(0, recovery.recoverAndReconcile(NOW));
        assertEquals(1, recovery.recoverAndReconcile(NOW));
        assertEquals(last.getId(), queue.claimDue(NOW, 1, Duration.ofMinutes(1)).getFirst().orderId());
    }

    @Test
    void shouldRecoverExpiredClaimWithoutDuplicatingTheCompensatedTask() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        TradeOrder due = order(repository, NOW.minusMinutes(1), OrderStatus.PENDING);
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        queue.enqueue(due.getId(), due.getExpireAt());
        var abandoned = queue.claimDue(NOW, 1, Duration.ofSeconds(5)).getFirst();
        OrderTimeoutRecoveryService recovery = new OrderTimeoutRecoveryService(repository, queue,
                new OrderTimeoutMetrics(), 10, 0);

        assertEquals(0, recovery.recoverAndReconcile(NOW.plusSeconds(5)));
        assertEquals(1, queue.snapshot(NOW.plusSeconds(5)).pending());
        assertFalse(queue.ack(abandoned));
    }

    private TradeOrder order(InMemoryTradeOrderRepository repository, LocalDateTime deadline, OrderStatus status) {
        TradeOrder order = new TradeOrder();
        order.setStatus(status);
        order.setExpireAt(deadline);
        return repository.save(order);
    }
}
