package com.campustrade.order.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.order.model.OrderStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class OrderStateMachine {

    private final Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);

    public OrderStateMachine() {
        transitions.put(OrderStatus.PENDING, EnumSet.of(
                OrderStatus.CONFIRMED,
                OrderStatus.REJECTED,
                OrderStatus.CANCELLED,
                OrderStatus.EXPIRED
        ));
        transitions.put(OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.CANCELLED, OrderStatus.COMPLETED));
        transitions.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.EXPIRED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class));
    }

    public void assertCanTransit(OrderStatus from, OrderStatus to) {
        if (!transitions.getOrDefault(from, Set.of()).contains(to)) {
            throw BizException.conflict("非法订单状态流转：" + from + " -> " + to);
        }
    }
}
