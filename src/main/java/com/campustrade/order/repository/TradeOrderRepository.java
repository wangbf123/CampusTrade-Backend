package com.campustrade.order.repository;

import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface TradeOrderRepository {

    TradeOrder save(TradeOrder order);

    Optional<TradeOrder> findById(Long id);

    List<TradeOrder> findByBuyerId(Long buyerId);

    List<TradeOrder> findByBuyerId(Long buyerId, int page, int size);

    List<TradeOrder> findBySellerId(Long sellerId);

    List<TradeOrder> findBySellerId(Long sellerId, int page, int size);

    List<TradeOrder> findExpiredPending(LocalDateTime now);

    List<TradeOrder> findExpiredPending(LocalDateTime now, int limit);

    List<TradeOrder> findPendingExpiringBefore(LocalDateTime horizon, LocalDateTime afterExpireAt,
                                              Long afterId, int limit);

    boolean updateStatusIfCurrent(Long orderId, OrderStatus expected, OrderStatus next, Consumer<TradeOrder> mutation);

    boolean confirmIfPendingAndNotExpired(Long orderId, Consumer<TradeOrder> mutation);

    boolean expireIfPendingAndDue(Long orderId, Consumer<TradeOrder> mutation);
}
