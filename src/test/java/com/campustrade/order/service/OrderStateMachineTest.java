package com.campustrade.order.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.order.model.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @Test
    void shouldAllowPendingToConfirmed() {
        assertDoesNotThrow(() -> stateMachine.assertCanTransit(OrderStatus.PENDING, OrderStatus.CONFIRMED));
    }

    @Test
    void shouldRejectCompletedToCancelled() {
        assertThrows(BizException.class,
                () -> stateMachine.assertCanTransit(OrderStatus.COMPLETED, OrderStatus.CANCELLED));
    }
}
