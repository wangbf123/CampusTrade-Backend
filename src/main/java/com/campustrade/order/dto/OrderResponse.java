package com.campustrade.order.dto;

import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;

import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        String orderNo,
        Long itemId,
        Long buyerId,
        Long sellerId,
        OrderStatus status,
        LocalDateTime expectedTime,
        String note,
        LocalDateTime expireAt,
        LocalDateTime confirmedAt,
        LocalDateTime completedAt,
        String cancelReason,
        long version,
        LocalDateTime createdAt
) {
    public static OrderResponse from(TradeOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getItemId(),
                order.getBuyerId(),
                order.getSellerId(),
                order.getStatus(),
                order.getExpectedTime(),
                order.getNote(),
                order.getExpireAt(),
                order.getConfirmedAt(),
                order.getCompletedAt(),
                order.getCancelReason(),
                order.getVersion(),
                order.getCreatedAt()
        );
    }
}
