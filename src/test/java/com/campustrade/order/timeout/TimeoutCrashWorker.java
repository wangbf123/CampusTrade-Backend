package com.campustrade.order.timeout;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** A real child JVM deliberately killed at the two protocol failure boundaries. */
public final class TimeoutCrashWorker {
    public static void main(String[] arguments) throws Exception {
        String database = arguments[0];
        String key = arguments[1];
        String phase = arguments[2];
        try (TimeoutIntegrationFixture fixture = new TimeoutIntegrationFixture(database, key)) {
            if (phase.equals("CLAIM")) {
                var claims = fixture.queue().claimDue(LocalDateTime.now(ZoneOffset.UTC), 50, Duration.ofSeconds(2));
                if (claims.isEmpty()) {
                    throw new IllegalStateException("Worker has no due task to claim");
                }
                System.out.println("CLAIMED");
                System.out.flush();
                System.in.read();
            } else {
                RedisOrderTimeoutQueue blockedAck = new RedisOrderTimeoutQueue(fixture.redis, key) {
                    @Override
                    public boolean ack(OrderTimeoutClaim claim) {
                        System.out.println("DATABASE_COMMITTED");
                        System.out.flush();
                        try {
                            System.in.read();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        return super.ack(claim);
                    }
                };
                fixture.service(blockedAck).expireDueOrders();
            }
        }
    }
}
