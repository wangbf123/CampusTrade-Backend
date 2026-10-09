package com.campustrade.order.timeout;

import com.campustrade.observability.OrderTimeoutMetrics;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.order.repository.TradeOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Bounded keyset scan makes the database the durable source even after complete Redis loss. */
@Service
public class OrderTimeoutRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutRecoveryService.class);
    private final TradeOrderRepository orderRepository;
    private final OrderTimeoutQueue queue;
    private final OrderTimeoutMetrics metrics;
    private final int batchSize;
    private final long lookaheadSeconds;
    private LocalDateTime cursorExpireAt;
    private Long cursorId;

    public OrderTimeoutRecoveryService(TradeOrderRepository orderRepository, OrderTimeoutQueue queue,
                                       OrderTimeoutMetrics metrics,
                                       @Value("${app.order-timeout.recovery-batch-size:500}") int batchSize,
                                       @Value("${app.order-timeout.lookahead-seconds:60}") long lookaheadSeconds) {
        this.orderRepository = orderRepository;
        this.queue = queue;
        this.metrics = metrics;
        this.batchSize = Math.max(1, batchSize);
        this.lookaheadSeconds = Math.max(0, lookaheadSeconds);
    }

    @Scheduled(fixedDelayString = "${app.order-timeout.recovery-delay:10000}")
    public void recoverAndReconcile() {
        recoverAndReconcile(LocalDateTime.now(ZoneOffset.UTC));
    }

    public synchronized int recoverAndReconcile(LocalDateTime now) {
        try {
            metrics.recovered(queue.recoverExpired(now, batchSize));
            List<TradeOrder> page = orderRepository.findPendingExpiringBefore(
                    now.plusSeconds(lookaheadSeconds), cursorExpireAt, cursorId, batchSize);
            int added = 0;
            for (TradeOrder order : page) {
                if (queue.enqueueIfMissing(order.getId(), order.getExpireAt())) {
                    added++;
                }
            }
            // Move the cursor only after the whole page was reconciled successfully.
            if (page.size() < batchSize) {
                cursorExpireAt = null;
                cursorId = null;
            } else {
                TradeOrder last = page.getLast();
                cursorExpireAt = last.getExpireAt();
                cursorId = last.getId();
            }
            metrics.compensated(added);
            metrics.scanSucceeded(queue.snapshot(now), now.toEpochSecond(ZoneOffset.UTC));
            return added;
        } catch (Exception exception) {
            metrics.backendFailure();
            log.warn("Order timeout recovery failed; durable database fallback remains available", exception);
            return 0;
        }
    }
}
