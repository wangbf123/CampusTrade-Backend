package com.campustrade.order.timeout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Uses a unique hash-tagged namespace; never flushes the shared Redis instance. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_REDIS_IT", matches = "true")
class RedisOrderTimeoutQueueIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 8, 0);
    private static final Duration LEASE = Duration.ofSeconds(10);
    private LettuceConnectionFactory connections;
    private StringRedisTemplate template;
    private RedisOrderTimeoutQueue queue;
    private String key;

    @BeforeEach
    void connect() {
        connections = new LettuceConnectionFactory(System.getenv().getOrDefault("CAMPUS_REDIS_IT_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("CAMPUS_REDIS_IT_PORT", "6379")));
        connections.afterPropertiesSet();
        connections.start();
        template = new StringRedisTemplate(connections);
        key = "{timeout-it-" + UUID.randomUUID() + "}:pending";
        queue = new RedisOrderTimeoutQueue(template, key);
    }

    @AfterEach
    void cleanup() {
        if (template != null) {
            template.delete(List.of(key, key + ":processing", key + ":tokens"));
        }
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    void crashLeaseRecoveryAndStaleTokenFencing() {
        queue.enqueue(1L, NOW.minusMinutes(1));
        OrderTimeoutClaim lost = queue.claimDue(NOW, 1, LEASE).getFirst();
        assertEquals(1, queue.snapshot(NOW).processing());
        assertFalse(queue.enqueueIfMissing(1L, NOW.minusHours(1)));
        assertTrue(queue.claimDue(NOW, 1, LEASE).isEmpty());
        assertEquals(0, queue.recoverExpired(NOW.plusSeconds(9), 1));
        assertEquals(1, queue.recoverExpired(NOW.plusSeconds(10), 1));
        OrderTimeoutClaim replacement = queue.claimDue(NOW.plusSeconds(10), 1, LEASE).getFirst();

        assertNotEquals(lost.token(), replacement.token());
        assertFalse(queue.ack(lost));
        assertFalse(queue.retry(lost, NOW.plusHours(1)));
        assertEquals(1, queue.snapshot(NOW).processing());
        assertTrue(queue.ack(replacement));
        assertFalse(queue.ack(replacement));
        assertEquals(0, queue.snapshot(NOW).processing());
    }

    @Test
    void databaseCompensationDoesNotShortCircuitRetryBackoff() {
        queue.enqueue(1L, NOW);
        OrderTimeoutClaim claim = queue.claimDue(NOW, 1, LEASE).getFirst();
        assertTrue(queue.retry(claim, NOW.plusMinutes(1)));
        assertFalse(queue.enqueueIfMissing(1L, NOW.minusDays(1)));
        assertTrue(queue.claimDue(NOW, 10, LEASE).isEmpty());
        assertEquals(1, queue.claimDue(NOW.plusMinutes(1), 10, LEASE).size());
    }

    @Test
    void concurrentWorkersNeverOwnTheSameActiveClaim() throws Exception {
        for (long id = 1; id <= 80; id++) {
            queue.enqueue(id, NOW);
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(worker -> executor.submit(() -> {
                start.await();
                return queue.claimDue(NOW, 40, LEASE);
            })).toList();
            start.countDown();
            var allClaims = new java.util.ArrayList<OrderTimeoutClaim>();
            for (var future : futures) {
                allClaims.addAll(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(80, allClaims.size());
            assertEquals(80, allClaims.stream().map(OrderTimeoutClaim::orderId).distinct().count());
            assertEquals(80, queue.snapshot(NOW).processing());
        }
    }

    @Test
    void utcScoresRemainStableAcrossHostTimezones() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            queue.enqueue(1L, NOW);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(1, queue.claimDue(NOW, 1, LEASE).size());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void boundsRecoveryAndReportsOldestDueAge() {
        queue.enqueue(1L, NOW.minusSeconds(12));
        queue.enqueue(2L, NOW.minusSeconds(3));
        assertEquals(12, queue.snapshot(NOW).oldestDueAgeSeconds(), 0.001);
        queue.claimDue(NOW, 2, LEASE);
        assertEquals(1, queue.recoverExpired(NOW.plusSeconds(10), 1));
        assertEquals(1, queue.snapshot(NOW.plusSeconds(10)).expiredLeases());
        queue.remove(1L);
        queue.remove(2L);
        assertEquals(0, queue.recoverExpired(NOW.plusMinutes(1), 10));
    }
}
