package com.campustrade.risk.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("!redis")
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int permits, Duration window) {
        Instant now = Instant.now();
        Counter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new Counter(new AtomicInteger(0), now.plus(window));
            }
            return current;
        });
        return counter.count().incrementAndGet() <= permits;
    }

    private record Counter(AtomicInteger count, Instant expiresAt) {
    }
}
