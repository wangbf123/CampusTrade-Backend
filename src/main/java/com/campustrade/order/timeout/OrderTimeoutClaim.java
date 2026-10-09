package com.campustrade.order.timeout;

import java.time.LocalDateTime;

/** The token fences acknowledgements and retries from a superseded worker. */
public record OrderTimeoutClaim(Long orderId, String token, LocalDateTime leaseUntil) {
}
