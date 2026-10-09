package com.campustrade.order.timeout;

public record TimeoutQueueSnapshot(long pending, long processing, long expiredLeases, double oldestDueAgeSeconds) {
}
