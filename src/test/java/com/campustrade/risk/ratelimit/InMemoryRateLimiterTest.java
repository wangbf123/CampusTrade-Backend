package com.campustrade.risk.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRateLimiterTest {

    @Test
    void shouldRejectWhenPermitExceededInWindow() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();

        assertTrue(limiter.tryAcquire("rate:test", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("rate:test", 2, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("rate:test", 2, Duration.ofMinutes(1)));
    }
}
