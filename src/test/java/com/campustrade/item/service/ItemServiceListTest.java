package com.campustrade.item.service;

import com.campustrade.cache.CacheProperties;
import com.campustrade.cache.InMemoryHotItemRankService;
import com.campustrade.cache.InMemoryItemDetailCache;
import com.campustrade.item.dto.CreateItemRequest;
import com.campustrade.item.dto.ItemQuery;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.item.model.ConditionLevel;
import com.campustrade.item.repository.InMemoryItemRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemServiceListTest {

    @Test
    void listShouldReturnRequestedPageOnly() {
        ItemService itemService = newItemService();
        ItemResponse first = createItem(itemService, "Book A", "图书");
        ItemResponse second = createItem(itemService, "Book B", "图书");
        ItemResponse third = createItem(itemService, "Book C", "图书");

        List<ItemResponse> pageOne = itemService.list(new ItemQuery(null, null, null, null, null, null, 1, 2));
        List<ItemResponse> pageTwo = itemService.list(new ItemQuery(null, null, null, null, null, null, 2, 2));

        assertEquals(List.of(third.id(), second.id()), pageOne.stream().map(ItemResponse::id).toList());
        assertEquals(List.of(first.id()), pageTwo.stream().map(ItemResponse::id).toList());
    }

    @Test
    void listShouldTrimKeywordAndApplySizeLimit() {
        ItemService itemService = newItemService();
        createItem(itemService, "iPad Air", "电子产品");
        createItem(itemService, "Textbook", "图书");

        List<ItemResponse> items = itemService.list(new ItemQuery(" iPad ", null, null, null, null, null, 1, 200));

        assertEquals(1, items.size());
        assertEquals("iPad Air", items.getFirst().title());
    }

    private ItemResponse createItem(ItemService itemService, String title, String category) {
        return itemService.create(1L, new CreateItemRequest(
                title,
                title + " description",
                category,
                new BigDecimal("99.00"),
                ConditionLevel.LIKE_NEW,
                "前卫南区",
                "图书馆门口",
                List.of()
        ));
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
