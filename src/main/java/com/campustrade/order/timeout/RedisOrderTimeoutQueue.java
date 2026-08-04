package com.campustrade.order.timeout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

@Component
@Profile("redis")
public class RedisOrderTimeoutQueue implements OrderTimeoutQueue {

    private static final DefaultRedisScript<List> POP_DUE_SCRIPT = new DefaultRedisScript<>("""
            local values = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            if #values > 0 then
                redis.call('ZREM', KEYS[1], unpack(values))
            end
            return values
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final String queueKey;

    public RedisOrderTimeoutQueue(
            StringRedisTemplate redisTemplate,
            @Value("${app.order-timeout.queue-key:order:timeout:zset}") String queueKey
    ) {
        this.redisTemplate = redisTemplate;
        this.queueKey = queueKey;
    }

    @Override
    public void enqueue(Long orderId, LocalDateTime expireAt) {
        redisTemplate.opsForZSet().add(queueKey, String.valueOf(orderId), toEpochMilli(expireAt));
    }

    @Override
    public List<Long> dueOrderIds(LocalDateTime now, int limit) {
        List<?> values = redisTemplate.execute(
                POP_DUE_SCRIPT,
                List.of(queueKey),
                String.valueOf(toEpochMilli(now)),
                String.valueOf(Math.max(1, limit))
        );
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream().map(String::valueOf).map(Long::valueOf).toList();
    }

    @Override
    public void remove(Long orderId) {
        redisTemplate.opsForZSet().remove(queueKey, String.valueOf(orderId));
    }

    private double toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
