package com.campustrade.order.timeout;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryOrderTimeoutQueueTest {

    @Test
    void shouldReturnOnlyDueOrdersByExpireTime() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        LocalDateTime now = LocalDateTime.now();
        queue.enqueue(1L, now.plusMinutes(10));
        queue.enqueue(2L, now.minusMinutes(1));
        queue.enqueue(3L, now.minusMinutes(5));

        List<Long> dueOrderIds = queue.dueOrderIds(now, 10);

        assertEquals(List.of(3L, 2L), dueOrderIds);
    }

    @Test
    void shouldRemoveOrderFromTimeoutQueue() {
        InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        LocalDateTime now = LocalDateTime.now();
        queue.enqueue(1L, now.minusMinutes(1));

        queue.remove(1L);

        assertTrue(queue.dueOrderIds(now, 10).isEmpty());
    }
}
