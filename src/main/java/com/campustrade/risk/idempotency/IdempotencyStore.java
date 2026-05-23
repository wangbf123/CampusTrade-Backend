package com.campustrade.risk.idempotency;

import java.time.Duration;

public interface IdempotencyStore {

    boolean tryAcquire(String key, Duration ttl);
}
