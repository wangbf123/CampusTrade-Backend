package com.campustrade.order.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.order.mapper.TradeOrderMapper;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
        LocalDateTime now = LocalDateTime.now();
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
    public boolean updateStatusIfCurrent(
            Long orderId,
            OrderStatus expected,
            OrderStatus next,
            Consumer<TradeOrder> mutation
    ) {
        Optional<TradeOrder> optionalOrder = findById(orderId);
        if (optionalOrder.isEmpty() || optionalOrder.get().getStatus() != expected) {
            return false;
        }

        TradeOrder order = optionalOrder.get();
        order.setStatus(next);
        if (mutation != null) {
            mutation.accept(order);
        }

        int rows = tradeOrderMapper.update(null, new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getId, orderId)
                .eq(TradeOrder::getStatus, expected)
                .set(TradeOrder::getStatus, next)
                .set(TradeOrder::getConfirmedAt, order.getConfirmedAt())
                .set(TradeOrder::getCompletedAt, order.getCompletedAt())
                .set(TradeOrder::getCancelReason, order.getCancelReason())
                .set(TradeOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        return rows == 1;
    }
}
