package com.campustrade.task;

import com.campustrade.order.service.TradeOrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutTask {

    private final TradeOrderService tradeOrderService;

    public OrderTimeoutTask(TradeOrderService tradeOrderService) {
        this.tradeOrderService = tradeOrderService;
    }

    @Scheduled(fixedDelayString = "${app.order-timeout.scan-delay:60000}")
    public void expireDueOrders() {
        tradeOrderService.expireDueOrders();
    }
}
