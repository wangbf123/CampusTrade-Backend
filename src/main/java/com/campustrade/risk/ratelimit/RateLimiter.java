package com.campustrade.risk.ratelimit;

import java.time.Duration;

public interface RateLimiter {

    boolean tryAcquire(String key, int permits, Duration window);
}
