package com.campustrade.order.timeout;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderTimeoutQueue {

    void enqueue(Long orderId, LocalDateTime expireAt);

    List<Long> dueOrderIds(LocalDateTime now, int limit);

    void remove(Long orderId);
}
