package com.campustrade.order.timeout;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryOrderTimeoutQueue implements OrderTimeoutQueue {

    private final Map<Long, LocalDateTime> expireTimes = new ConcurrentHashMap<>();
    private final Map<Long, OrderTimeoutClaim> processing = new ConcurrentHashMap<>();

    @Override
    public void enqueue(Long orderId, LocalDateTime expireAt) {
        enqueueIfMissing(orderId, expireAt);
    }

    @Override
    public synchronized boolean enqueueIfMissing(Long orderId, LocalDateTime expireAt) {
        if (processing.containsKey(orderId) || expireTimes.containsKey(orderId)) {
            return false;
        }
        expireTimes.put(orderId, expireAt);
        return true;
    }

    @Override
    public synchronized List<OrderTimeoutClaim> claimDue(LocalDateTime now, int limit, Duration lease) {
        if (lease.isZero() || lease.isNegative() || lease.toMillis() < 1) {
            throw new IllegalArgumentException("Lease must be at least one millisecond");
        }
        if (limit <= 0) {
            return List.of();
        }
        List<Long> dueIds = expireTimes.entrySet().stream()
                .filter(entry -> !entry.getValue().isAfter(now))
                .sorted(Comparator.<Map.Entry<Long, LocalDateTime>, LocalDateTime>comparing(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
        dueIds.forEach(expireTimes::remove);
        return dueIds.stream().map(id -> {
            OrderTimeoutClaim claim = new OrderTimeoutClaim(id, UUID.randomUUID().toString(), now.plus(lease));
            processing.put(id, claim);
            return claim;
        }).toList();
    }

    @Override
    public synchronized boolean ack(OrderTimeoutClaim claim) {
        OrderTimeoutClaim current = processing.get(claim.orderId());
        if (current == null || !current.token().equals(claim.token())) {
            return false;
        }
        processing.remove(claim.orderId());
        return true;
    }

    @Override
    public synchronized boolean retry(OrderTimeoutClaim claim, LocalDateTime retryAt) {
        if (!ack(claim)) {
            return false;
        }
        expireTimes.put(claim.orderId(), retryAt);
        return true;
    }

    @Override
    public synchronized int recoverExpired(LocalDateTime now, int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<OrderTimeoutClaim> expired = processing.values().stream()
                .filter(claim -> !claim.leaseUntil().isAfter(now))
                .sorted(Comparator.comparing(OrderTimeoutClaim::leaseUntil))
                .limit(limit)
                .toList();
        expired.forEach(claim -> {
            processing.remove(claim.orderId());
            expireTimes.putIfAbsent(claim.orderId(), now);
        });
        return expired.size();
    }

    @Override
    public synchronized TimeoutQueueSnapshot snapshot(LocalDateTime now) {
        long expired = processing.values().stream().filter(claim -> !claim.leaseUntil().isAfter(now)).count();
        double oldestAge = expireTimes.values().stream().min(LocalDateTime::compareTo)
                .map(time -> Math.max(0, Duration.between(time, now).toMillis() / 1000.0)).orElse(0.0);
        return new TimeoutQueueSnapshot(expireTimes.size(), processing.size(), expired, oldestAge);
    }

    @Override
    public synchronized void remove(Long orderId) {
        expireTimes.remove(orderId);
        processing.remove(orderId);
    }
}
