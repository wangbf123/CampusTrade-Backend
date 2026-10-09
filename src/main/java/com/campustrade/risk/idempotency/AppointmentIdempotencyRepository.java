package com.campustrade.risk.idempotency;

import com.campustrade.order.dto.OrderResponse;

import java.util.function.Supplier;

/** A successful explicit request key retains the original response for retries. */
public interface AppointmentIdempotencyRepository {
    OrderResponse execute(Long userId, String key, String requestHash, Supplier<OrderResponse> operation);
}
