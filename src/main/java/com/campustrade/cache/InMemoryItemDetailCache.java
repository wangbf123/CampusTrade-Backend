package com.campustrade.cache;

import com.campustrade.item.dto.ItemResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Profile("!redis")
public class InMemoryItemDetailCache implements ItemDetailCache {

    private final CacheProperties properties;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public InMemoryItemDetailCache(CacheProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<CachedItemDetail> get(Long itemId) {
        CacheEntry entry = cache.get(itemId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            cache.remove(itemId);
            return Optional.empty();
        }
        return Optional.of(entry.detail());
    }

    @Override
    public void putItem(ItemResponse item) {
        cache.put(item.id(), new CacheEntry(CachedItemDetail.hit(item), expiresAt(properties.getItemDetailTtlSeconds())));
    }

    @Override
    public void putNull(Long itemId) {
        cache.put(itemId, new CacheEntry(CachedItemDetail.empty(), expiresAt(properties.getNullItemTtlSeconds())));
    }

    @Override
    public void evict(Long itemId) {
        cache.remove(itemId);
    }

    private Instant expiresAt(long baseTtlSeconds) {
        long jitter = properties.getRandomJitterSeconds() <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.getRandomJitterSeconds() + 1);
        return Instant.now().plusSeconds(baseTtlSeconds + jitter);
    }

    private record CacheEntry(CachedItemDetail detail, Instant expiresAt) {
    }
}
