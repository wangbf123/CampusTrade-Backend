package com.campustrade.order.concurrency;

import com.campustrade.common.exception.BizException;
import com.campustrade.order.dto.OrderResponse;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.risk.idempotency.InMemoryAppointmentIdempotencyRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAppointmentIdempotencyTest {

    @Test
    void concurrentDuplicateRequestsExecuteOnceAndReplayTheSameResponse() throws Exception {
        InMemoryAppointmentIdempotencyRepository repository = new InMemoryAppointmentIdempotencyRepository();
        OrderResponse response = response(1L);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResponse>> attempts = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 32; i++) {
                attempts.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return repository.execute(100L, "same-request", "same-payload", () -> {
                        executions.incrementAndGet();
                        return response;
                    });
                }));
            }
            start.countDown();
            for (Future<OrderResponse> attempt : attempts) {
                assertEquals(response, attempt.get(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, executions.get());
    }

    @Test
    void differentPayloadConflictsButDifferentUsersHaveIndependentKeys() {
        InMemoryAppointmentIdempotencyRepository repository = new InMemoryAppointmentIdempotencyRepository();
        OrderResponse first = repository.execute(100L, "same-key", "first", () -> response(1L));
        BizException conflict = assertThrows(BizException.class,
                () -> repository.execute(100L, "same-key", "changed", () -> {
                    fail("Conflicting payload must not execute");
                    return null;
                }));
        OrderResponse anotherUser = repository.execute(200L, "same-key", "changed", () -> response(2L));

        assertEquals(409, conflict.getStatus().value());
        assertEquals(1L, first.id());
        assertEquals(2L, anotherUser.id());
    }

    @Test
    void failedOperationDoesNotConsumeTheKey() {
        InMemoryAppointmentIdempotencyRepository repository = new InMemoryAppointmentIdempotencyRepository();
        assertThrows(IllegalStateException.class,
                () -> repository.execute(100L, "retryable", "same", () -> {
                    throw new IllegalStateException("injected failure");
                }));

        OrderResponse recovered = repository.execute(100L, "retryable", "same", () -> response(3L));

        assertEquals(3L, recovered.id());
        assertEquals(recovered, repository.execute(100L, "retryable", "same", () -> {
            fail("A successful retry must subsequently replay");
            return null;
        }));
    }

    private OrderResponse response(Long id) {
        TradeOrder order = new TradeOrder();
        order.setId(id);
        order.setOrderNo("CT" + id);
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        return OrderResponse.from(order);
    }
}
