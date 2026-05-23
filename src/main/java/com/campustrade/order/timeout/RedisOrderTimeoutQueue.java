package com.campustrade.order.timeout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
@Profile("redis")
public class RedisOrderTimeoutQueue implements OrderTimeoutQueue {

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
        Set<String> values = redisTemplate.opsForZSet()
                .rangeByScore(queueKey, 0, toEpochMilli(now), 0, Math.max(1, limit));
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream().map(Long::valueOf).toList();
    }

    @Override
    public void remove(Long orderId) {
        redisTemplate.opsForZSet().remove(queueKey, String.valueOf(orderId));
    }

    private double toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
