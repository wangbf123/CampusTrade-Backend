package com.campustrade.item.service;

import com.campustrade.cache.CacheProperties;
import com.campustrade.cache.InMemoryHotItemRankService;
import com.campustrade.cache.InMemoryItemDetailCache;
import com.campustrade.common.exception.BizException;
import com.campustrade.item.dto.CreateItemRequest;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.item.model.ConditionLevel;
import com.campustrade.item.repository.InMemoryItemRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemServiceCacheTest {

    @Test
    void shouldRecordHotRankWhenItemDetailIsViewed() {
        ItemService itemService = newItemService();
        ItemResponse item = itemService.create(1L, new CreateItemRequest(
                "iPad Air 5",
                "自用平板",
                "电子产品",
                new BigDecimal("2599.00"),
                ConditionLevel.LIKE_NEW,
                "东校区",
                "图书馆一楼",
                List.of()
        ));

        itemService.detail(item.id());
        itemService.detail(item.id());

        List<ItemResponse> hotItems = itemService.hotItems(10);
        assertEquals(1, hotItems.size());
        assertEquals(item.id(), hotItems.getFirst().id());
    }

    @Test
    void shouldCacheNullValueForMissingItem() {
        CacheProperties properties = new CacheProperties();
        properties.setNullItemTtlSeconds(Duration.ofMinutes(5).toSeconds());
        InMemoryItemDetailCache cache = new InMemoryItemDetailCache(properties);
        ItemService itemService = new ItemService(new InMemoryItemRepository(), cache, new InMemoryHotItemRankService());

        assertThrows(BizException.class, () -> itemService.detail(999L));
        assertEquals(false, cache.get(999L).orElseThrow().exists());
    }

    private ItemService newItemService() {
        CacheProperties properties = new CacheProperties();
        return new ItemService(
                new InMemoryItemRepository(),
                new InMemoryItemDetailCache(properties),
                new InMemoryHotItemRankService()
        );
    }
}
