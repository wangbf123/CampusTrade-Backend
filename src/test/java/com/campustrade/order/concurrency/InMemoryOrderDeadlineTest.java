package com.campustrade.order.concurrency;

import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.order.repository.InMemoryTradeOrderRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryOrderDeadlineTest {

    @Test
    void pendingOrderCanOnlyBeConfirmedBeforeItsDeadline() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        TradeOrder future = pending(repository, LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        TradeOrder expired = pending(repository, LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        AtomicInteger mutations = new AtomicInteger();
        long expiredVersion = expired.getVersion();

        assertTrue(repository.confirmIfPendingAndNotExpired(future.getId(), target -> {
            mutations.incrementAndGet();
            target.setConfirmedAt(LocalDateTime.now(ZoneOffset.UTC));
        }));
        assertFalse(repository.confirmIfPendingAndNotExpired(expired.getId(), target -> mutations.incrementAndGet()));
        assertFalse(repository.confirmIfPendingAndNotExpired(future.getId(), target -> mutations.incrementAndGet()));

        assertEquals(1, mutations.get());
        assertEquals(OrderStatus.CONFIRMED, future.getStatus());
        assertNotNull(future.getConfirmedAt());
        assertEquals(OrderStatus.PENDING, expired.getStatus());
        assertNull(expired.getConfirmedAt());
        assertEquals(expiredVersion, expired.getVersion());
    }

    @Test
    void earlyTimeoutDeliveryDoesNotExpireAnOrder() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        TradeOrder future = pending(repository, LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        long version = future.getVersion();
        AtomicInteger mutations = new AtomicInteger();

        assertFalse(repository.expireIfPendingAndDue(future.getId(), target -> mutations.incrementAndGet()));

        assertEquals(OrderStatus.PENDING, future.getStatus());
        assertEquals(version, future.getVersion());
        assertEquals(0, mutations.get());
    }

    @Test
    void concurrentTimeoutDeliveriesApplyTheTransitionOnce() throws Exception {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        TradeOrder due = pending(repository, LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        long version = due.getVersion();
        AtomicInteger mutations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 32; i++) {
                attempts.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return repository.expireIfPendingAndDue(due.getId(), target -> mutations.incrementAndGet());
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(1, successes);
        }

        assertEquals(OrderStatus.EXPIRED, due.getStatus());
        assertEquals(version + 1, due.getVersion());
        assertEquals(1, mutations.get());
        assertFalse(repository.confirmIfPendingAndNotExpired(due.getId(), null));
    }

    @Test
    void missingOrTerminalOrdersDoNotExecuteDeadlineMutations() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        AtomicInteger mutations = new AtomicInteger();
        assertFalse(repository.confirmIfPendingAndNotExpired(999L, target -> mutations.incrementAndGet()));
        assertFalse(repository.expireIfPendingAndDue(999L, target -> mutations.incrementAndGet()));
        for (OrderStatus status : List.of(OrderStatus.CANCELLED, OrderStatus.REJECTED,
                OrderStatus.COMPLETED, OrderStatus.CONFIRMED, OrderStatus.EXPIRED)) {
            TradeOrder order = pending(repository, LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
            order.setStatus(status);
            assertFalse(repository.confirmIfPendingAndNotExpired(order.getId(), target -> mutations.incrementAndGet()));
            assertFalse(repository.expireIfPendingAndDue(order.getId(), target -> mutations.incrementAndGet()));
            assertEquals(status, order.getStatus());
        }
        assertEquals(0, mutations.get());
    }

    private TradeOrder pending(InMemoryTradeOrderRepository repository, LocalDateTime expireAt) {
        TradeOrder order = new TradeOrder();
        order.setStatus(OrderStatus.PENDING);
        order.setExpireAt(expireAt);
        return repository.save(order);
    }
}
