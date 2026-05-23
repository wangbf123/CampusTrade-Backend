package com.campustrade.cache;

import com.campustrade.item.dto.ItemResponse;

import java.util.Optional;

public interface ItemDetailCache {

    Optional<CachedItemDetail> get(Long itemId);

    void putItem(ItemResponse item);

    void putNull(Long itemId);

    void evict(Long itemId);
}
