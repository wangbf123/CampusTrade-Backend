package com.campustrade.cache;

import com.campustrade.item.dto.ItemResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Profile("redis")
public class RedisItemDetailCache implements ItemDetailCache {

    private static final String KEY_PREFIX = "item:detail:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;

    public RedisItemDetailCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, CacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<CachedItemDetail> get(Long itemId) {
        String payload = redisTemplate.opsForValue().get(key(itemId));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, CachedItemDetail.class));
        } catch (JsonProcessingException exception) {
            redisTemplate.delete(key(itemId));
            return Optional.empty();
        }
    }

    @Override
    public void putItem(ItemResponse item) {
        write(item.id(), CachedItemDetail.hit(item), ttl(properties.getItemDetailTtlSeconds()));
    }

    @Override
    public void putNull(Long itemId) {
        write(itemId, CachedItemDetail.empty(), ttl(properties.getNullItemTtlSeconds()));
    }

    @Override
    public void evict(Long itemId) {
        redisTemplate.delete(key(itemId));
    }

    private void write(Long itemId, CachedItemDetail detail, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(itemId), objectMapper.writeValueAsString(detail), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize item cache", exception);
        }
    }

    private Duration ttl(long baseTtlSeconds) {
        long jitter = properties.getRandomJitterSeconds() <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.getRandomJitterSeconds() + 1);
        return Duration.ofSeconds(baseTtlSeconds + jitter);
    }

    private String key(Long itemId) {
        return KEY_PREFIX + itemId;
    }
}
