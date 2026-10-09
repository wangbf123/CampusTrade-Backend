package com.campustrade.order.timeout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A leased, at-least-once queue. Database status transitions provide business idempotency. */
@Component
@Profile("redis")
public class RedisOrderTimeoutQueue implements OrderTimeoutQueue {

    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) or redis.call('ZSCORE', KEYS[2], ARGV[1]) then
                return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local values = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            local result = {}
            for _, id in ipairs(values) do
                redis.call('ZREM', KEYS[1], id)
                if not redis.call('ZSCORE', KEYS[2], id) then
                    local token = ARGV[4] .. ':' .. id
                    redis.call('ZADD', KEYS[2], ARGV[3], id)
                    redis.call('HSET', KEYS[3], id, token)
                    table.insert(result, id)
                    table.insert(result, token)
                end
            end
            return result
            """, List.class);

    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then return 0 end
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RETRY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then return 0 end
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RECOVER_SCRIPT = new DefaultRedisScript<>("""
            local values = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            for _, id in ipairs(values) do
                redis.call('ZREM', KEYS[2], id)
                redis.call('HDEL', KEYS[3], id)
                redis.call('ZADD', KEYS[1], 'NX', ARGV[1], id)
            end
            return #values
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<List> SNAPSHOT_SCRIPT = new DefaultRedisScript<>("""
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            return {redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[2]),
                redis.call('ZCOUNT', KEYS[2], '-inf', ARGV[1]), oldest[2] or '0'}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final List<String> keys;

    public RedisOrderTimeoutQueue(
            StringRedisTemplate redisTemplate,
            @Value("${app.order-timeout.queue-key:{order-timeout}:pending}") String queueKey
    ) {
        this.redisTemplate = redisTemplate;
        // Even a legacy key without a hash tag produces three keys in the same Redis Cluster slot.
        String base = queueKey.contains("{") && queueKey.indexOf('}') > queueKey.indexOf('{') + 1
                ? queueKey : "{" + queueKey + "}:pending";
        this.keys = List.of(base, base + ":processing", base + ":tokens");
    }

    @Override
    public void enqueue(Long orderId, LocalDateTime expireAt) {
        enqueueIfMissing(orderId, expireAt);
    }

    @Override
    public boolean enqueueIfMissing(Long orderId, LocalDateTime expireAt) {
        return Long.valueOf(1).equals(redisTemplate.execute(ENQUEUE_SCRIPT, keys,
                String.valueOf(orderId), String.valueOf(toEpochMilli(expireAt))));
    }

    @Override
    public List<OrderTimeoutClaim> claimDue(LocalDateTime now, int limit, Duration lease) {
        if (lease.isNegative() || lease.isZero() || lease.toMillis() < 1) {
            throw new IllegalArgumentException("Lease must be at least one millisecond");
        }
        if (limit <= 0) {
            return List.of();
        }
        LocalDateTime leaseUntil = now.plus(lease);
        List<?> values = redisTemplate.execute(CLAIM_SCRIPT, keys,
                String.valueOf(toEpochMilli(now)), String.valueOf(limit),
                String.valueOf(toEpochMilli(leaseUntil)), UUID.randomUUID().toString());
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<OrderTimeoutClaim> claims = new ArrayList<>(values.size() / 2);
        for (int i = 0; i < values.size(); i += 2) {
            claims.add(new OrderTimeoutClaim(Long.valueOf(String.valueOf(values.get(i))),
                    String.valueOf(values.get(i + 1)), leaseUntil));
        }
        return List.copyOf(claims);
    }

    @Override
    public boolean ack(OrderTimeoutClaim claim) {
        return Long.valueOf(1).equals(redisTemplate.execute(ACK_SCRIPT, keys,
                String.valueOf(claim.orderId()), claim.token()));
    }

    @Override
    public boolean retry(OrderTimeoutClaim claim, LocalDateTime retryAt) {
        return Long.valueOf(1).equals(redisTemplate.execute(RETRY_SCRIPT, keys,
                String.valueOf(claim.orderId()), claim.token(), String.valueOf(toEpochMilli(retryAt))));
    }

    @Override
    public int recoverExpired(LocalDateTime now, int limit) {
        if (limit <= 0) {
            return 0;
        }
        Long count = redisTemplate.execute(RECOVER_SCRIPT, keys,
                String.valueOf(toEpochMilli(now)), String.valueOf(limit));
        return count == null ? 0 : count.intValue();
    }

    @Override
    public TimeoutQueueSnapshot snapshot(LocalDateTime now) {
        List<?> values = redisTemplate.execute(SNAPSHOT_SCRIPT, keys, String.valueOf(toEpochMilli(now)));
        if (values == null || values.size() != 4) {
            throw new IllegalStateException("Unable to read timeout queue snapshot");
        }
        long pending = Long.parseLong(String.valueOf(values.get(0)));
        double oldestScore = Double.parseDouble(String.valueOf(values.get(3)));
        double age = pending == 0 ? 0 : Math.max(0, (toEpochMilli(now) - oldestScore) / 1000.0);
        return new TimeoutQueueSnapshot(pending, Long.parseLong(String.valueOf(values.get(1))),
                Long.parseLong(String.valueOf(values.get(2))), age);
    }

    @Override
    public void remove(Long orderId) {
        redisTemplate.execute(REMOVE_SCRIPT, keys, String.valueOf(orderId));
    }

    private long toEpochMilli(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
