package com.campustrade.order.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.order.mapper.TradeOrderMapper;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Repository
@Profile("mysql")
public class MysqlTradeOrderRepository implements TradeOrderRepository {

    private final TradeOrderMapper tradeOrderMapper;

    public MysqlTradeOrderRepository(TradeOrderMapper tradeOrderMapper) {
        this.tradeOrderMapper = tradeOrderMapper;
    }

    @Override
    public TradeOrder save(TradeOrder order) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (order.getId() == null) {
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            tradeOrderMapper.insert(order);
            return order;
        }

        order.setVersion(order.getVersion() + 1);
        order.setUpdatedAt(now);
        tradeOrderMapper.updateById(order);
        return order;
    }

    @Override
    public Optional<TradeOrder> findById(Long id) {
        return Optional.ofNullable(tradeOrderMapper.selectById(id));
    }

    @Override
    public List<TradeOrder> findByBuyerId(Long buyerId) {
        return tradeOrderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getBuyerId, buyerId)
                .orderByDesc(TradeOrder::getCreatedAt));
    }

    @Override
    public List<TradeOrder> findByBuyerId(Long buyerId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        long offset = (long) (safePage - 1) * safeSize;
        return tradeOrderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getBuyerId, buyerId)
                .orderByDesc(TradeOrder::getCreatedAt)
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT " + offset + ", " + safeSize));
    }

    @Override
    public List<TradeOrder> findBySellerId(Long sellerId) {
        return tradeOrderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getSellerId, sellerId)
                .orderByDesc(TradeOrder::getCreatedAt));
    }

    @Override
    public List<TradeOrder> findBySellerId(Long sellerId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        long offset = (long) (safePage - 1) * safeSize;
        return tradeOrderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getSellerId, sellerId)
                .orderByDesc(TradeOrder::getCreatedAt)
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT " + offset + ", " + safeSize));
    }

    @Override
    public List<TradeOrder> findExpiredPending(LocalDateTime now) {
        return tradeOrderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getStatus, OrderStatus.PENDING)
                .le(TradeOrder::getExpireAt, now)
                .orderByAsc(TradeOrder::getExpireAt));
    }

    @Override
    public List<TradeOrder> findExpiredPending(LocalDateTime now, int limit) {
        return findPendingExpiringBefore(now, null, null, limit);
    }

    @Override
    public List<TradeOrder> findPendingExpiringBefore(LocalDateTime horizon, LocalDateTime afterExpireAt,
                                                     Long afterId, int limit) {
        LambdaQueryWrapper<TradeOrder> query = new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getStatus, OrderStatus.PENDING)
                .le(TradeOrder::getExpireAt, horizon);
        if (afterExpireAt != null) {
            query.and(cursor -> cursor.gt(TradeOrder::getExpireAt, afterExpireAt)
                    .or(equal -> equal.eq(TradeOrder::getExpireAt, afterExpireAt)
                            .gt(TradeOrder::getId, afterId)));
        }
        return tradeOrderMapper.selectList(query.orderByAsc(TradeOrder::getExpireAt)
                .orderByAsc(TradeOrder::getId).last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public boolean confirmIfPendingAndNotExpired(Long orderId, Consumer<TradeOrder> mutation) {
        return updateStatus(orderId, OrderStatus.PENDING, OrderStatus.CONFIRMED, mutation,
                "expire_at > CURRENT_TIMESTAMP(6)");
    }

    @Override
    public boolean expireIfPendingAndDue(Long orderId, Consumer<TradeOrder> mutation) {
        return updateStatus(orderId, OrderStatus.PENDING, OrderStatus.EXPIRED, mutation,
                "expire_at <= CURRENT_TIMESTAMP(6)");
    }

    @Override
    public boolean updateStatusIfCurrent(
            Long orderId,
            OrderStatus expected,
            OrderStatus next,
            Consumer<TradeOrder> mutation
    ) {
        return updateStatus(orderId, expected, next, mutation, null);
    }

    private boolean updateStatus(Long orderId, OrderStatus expected, OrderStatus next,
                                 Consumer<TradeOrder> mutation, String deadlineCondition) {
        Optional<TradeOrder> optionalOrder = findById(orderId);
        if (optionalOrder.isEmpty() || optionalOrder.get().getStatus() != expected) {
            return false;
        }

        TradeOrder order = optionalOrder.get();
        order.setStatus(next);
        if (mutation != null) {
            mutation.accept(order);
        }

        LambdaUpdateWrapper<TradeOrder> update = new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getId, orderId)
                .eq(TradeOrder::getStatus, expected)
                .set(TradeOrder::getStatus, next)
                .set(TradeOrder::getConfirmedAt, order.getConfirmedAt())
                .set(TradeOrder::getCompletedAt, order.getCompletedAt())
                .set(TradeOrder::getCancelReason, order.getCancelReason())
                .set(TradeOrder::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .setSql("version = version + 1");
        if (deadlineCondition != null) {
            update.apply(deadlineCondition);
        }
        int rows = tradeOrderMapper.update(null, update);
        return rows == 1;
    }
}
