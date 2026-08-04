package com.campustrade.order.repository;

import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryTradeOrderRepositoryTest {

    @Test
    void shouldPageBuyerOrdersByNewestFirst() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        LocalDateTime base = LocalDateTime.now();
        repository.save(order(1L, 100L, 200L, base.minusMinutes(3)));
        repository.save(order(2L, 100L, 201L, base.minusMinutes(2)));
        repository.save(order(3L, 100L, 202L, base.minusMinutes(1)));
        repository.save(order(4L, 101L, 200L, base));

        List<TradeOrder> page = repository.findByBuyerId(100L, 1, 2);

        assertEquals(List.of(3L, 2L), page.stream().map(TradeOrder::getId).toList());
    }

    @Test
    void shouldPageSellerOrdersByNewestFirst() {
        InMemoryTradeOrderRepository repository = new InMemoryTradeOrderRepository();
        LocalDateTime base = LocalDateTime.now();
        repository.save(order(1L, 100L, 200L, base.minusMinutes(3)));
        repository.save(order(2L, 101L, 200L, base.minusMinutes(2)));
        repository.save(order(3L, 102L, 200L, base.minusMinutes(1)));
        repository.save(order(4L, 103L, 201L, base));

        List<TradeOrder> page = repository.findBySellerId(200L, 2, 1);

        assertEquals(List.of(2L), page.stream().map(TradeOrder::getId).toList());
    }

    private TradeOrder order(Long id, Long buyerId, Long sellerId, LocalDateTime createdAt) {
        TradeOrder order = new TradeOrder();
        order.setId(id);
        order.setOrderNo("CT" + id);
        order.setItemId(300L + id);
        order.setBuyerId(buyerId);
        order.setSellerId(sellerId);
        order.setStatus(OrderStatus.PENDING);
        order.setExpectedTime(createdAt.plusDays(1));
        order.setExpireAt(createdAt.plusHours(24));
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        return order;
    }
}
