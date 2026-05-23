package com.campustrade.risk.idempotency;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Instant> keys = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        Instant now = Instant.now();
        keys.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        Instant expiresAt = now.plus(ttl);
        return keys.putIfAbsent(key, expiresAt) == null;
    }
}
