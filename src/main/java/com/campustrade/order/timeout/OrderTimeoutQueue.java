package com.campustrade.order.timeout;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

public interface OrderTimeoutQueue {

    void enqueue(Long orderId, LocalDateTime expireAt);

    /** Does not overwrite an existing pending task or an active claim. */
    boolean enqueueIfMissing(Long orderId, LocalDateTime expireAt);

    /** Atomically reserves tasks; successful database commit must precede acknowledgement. */
    List<OrderTimeoutClaim> claimDue(LocalDateTime now, int limit, Duration lease);

    boolean ack(OrderTimeoutClaim claim);

    boolean retry(OrderTimeoutClaim claim, LocalDateTime retryAt);

    int recoverExpired(LocalDateTime now, int limit);

    TimeoutQueueSnapshot snapshot(LocalDateTime now);

    void remove(Long orderId);
}
