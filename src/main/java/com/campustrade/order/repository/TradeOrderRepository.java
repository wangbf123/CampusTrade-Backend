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

    List<TradeOrder> findBySellerId(Long sellerId);

    List<TradeOrder> findExpiredPending(LocalDateTime now);

    boolean updateStatusIfCurrent(Long orderId, OrderStatus expected, OrderStatus next, Consumer<TradeOrder> mutation);
}
