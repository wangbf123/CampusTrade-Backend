package com.campustrade.order.repository;

import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Repository
@Profile("!mysql")
public class InMemoryTradeOrderRepository implements TradeOrderRepository {

    private final AtomicLong idGenerator = new AtomicLong(3000);
    private final Map<Long, TradeOrder> orders = new ConcurrentHashMap<>();

    @Override
    public synchronized TradeOrder save(TradeOrder order) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (order.getId() == null) {
            order.setId(idGenerator.incrementAndGet());
            order.setCreatedAt(now);
        }
        order.setVersion(order.getVersion() + 1);
        order.setUpdatedAt(now);
        orders.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<TradeOrder> findById(Long id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public List<TradeOrder> findByBuyerId(Long buyerId) {
        return orders.values().stream()
                .filter(order -> order.getBuyerId().equals(buyerId))
                .sorted(Comparator.comparing(TradeOrder::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<TradeOrder> findByBuyerId(Long buyerId, int page, int size) {
        return findByBuyerId(buyerId).stream()
                .skip(offset(page, size))
                .limit(Math.max(1, size))
                .toList();
    }

    @Override
    public List<TradeOrder> findBySellerId(Long sellerId) {
        return orders.values().stream()
                .filter(order -> order.getSellerId().equals(sellerId))
                .sorted(Comparator.comparing(TradeOrder::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<TradeOrder> findBySellerId(Long sellerId, int page, int size) {
        return findBySellerId(sellerId).stream()
                .skip(offset(page, size))
                .limit(Math.max(1, size))
                .toList();
    }

    @Override
    public List<TradeOrder> findExpiredPending(LocalDateTime now) {
        return orders.values().stream()
                .filter(order -> order.getStatus() == OrderStatus.PENDING)
                .filter(order -> order.getExpireAt() != null && !order.getExpireAt().isAfter(now))
                .toList();
    }

    @Override
    public List<TradeOrder> findExpiredPending(LocalDateTime now, int limit) {
        return findPendingExpiringBefore(now, null, null, limit);
    }

    @Override
    public List<TradeOrder> findPendingExpiringBefore(LocalDateTime horizon, LocalDateTime afterExpireAt,
                                                     Long afterId, int limit) {
        return orders.values().stream()
                .filter(order -> order.getStatus() == OrderStatus.PENDING)
                .filter(order -> order.getExpireAt() != null && !order.getExpireAt().isAfter(horizon))
                .filter(order -> afterExpireAt == null || order.getExpireAt().isAfter(afterExpireAt)
                        || (order.getExpireAt().equals(afterExpireAt) && order.getId() > afterId))
                .sorted(Comparator.comparing(TradeOrder::getExpireAt).thenComparing(TradeOrder::getId))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized boolean confirmIfPendingAndNotExpired(Long orderId, Consumer<TradeOrder> mutation) {
        TradeOrder order = orders.get(orderId);
        if (order == null || order.getExpireAt() == null || !order.getExpireAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            return false;
        }
        return updateStatusIfCurrent(orderId, OrderStatus.PENDING, OrderStatus.CONFIRMED, mutation);
    }

    @Override
    public synchronized boolean expireIfPendingAndDue(Long orderId, Consumer<TradeOrder> mutation) {
        TradeOrder order = orders.get(orderId);
        if (order == null || order.getExpireAt() == null || order.getExpireAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            return false;
        }
        return updateStatusIfCurrent(orderId, OrderStatus.PENDING, OrderStatus.EXPIRED, mutation);
    }

    @Override
    public synchronized boolean updateStatusIfCurrent(
            Long orderId,
            OrderStatus expected,
            OrderStatus next,
            Consumer<TradeOrder> mutation
    ) {
        TradeOrder order = orders.get(orderId);
        if (order == null || order.getStatus() != expected) {
            return false;
        }
        order.setStatus(next);
        if (mutation != null) {
            mutation.accept(order);
        }
        order.setVersion(order.getVersion() + 1);
        order.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }

    private long offset(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        return (long) (safePage - 1) * safeSize;
    }
}
