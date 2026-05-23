package com.campustrade.cache;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
@Profile("redis")
public class RedisHotItemRankService implements HotItemRankService {

    private final StringRedisTemplate redisTemplate;
    private final CacheProperties properties;

    public RedisHotItemRankService(StringRedisTemplate redisTemplate, CacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void recordView(Long itemId) {
        redisTemplate.opsForZSet().incrementScore(properties.getHotRankKey(), String.valueOf(itemId), 1D);
    }

    @Override
    public List<Long> topItemIds(int limit) {
        Set<String> values = redisTemplate.opsForZSet().reverseRange(properties.getHotRankKey(), 0, Math.max(0, limit - 1));
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream().map(Long::valueOf).toList();
    }

    @Override
    public void remove(Long itemId) {
        redisTemplate.opsForZSet().remove(properties.getHotRankKey(), String.valueOf(itemId));
    }
}
