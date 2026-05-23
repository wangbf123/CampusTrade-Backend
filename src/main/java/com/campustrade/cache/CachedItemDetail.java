package com.campustrade.cache;

import com.campustrade.item.dto.ItemResponse;

public record CachedItemDetail(boolean exists, ItemResponse item) {

    public static CachedItemDetail hit(ItemResponse item) {
        return new CachedItemDetail(true, item);
    }

    public static CachedItemDetail empty() {
        return new CachedItemDetail(false, null);
    }
}
