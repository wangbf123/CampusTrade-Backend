package com.campustrade.item.service;

import com.campustrade.cache.CachedItemDetail;
import com.campustrade.cache.HotItemRankService;
import com.campustrade.cache.ItemDetailCache;
import com.campustrade.common.exception.BizException;
import com.campustrade.item.dto.CreateItemRequest;
import com.campustrade.item.dto.ItemQuery;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.repository.ItemRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final ItemDetailCache itemDetailCache;
    private final HotItemRankService hotItemRankService;

    public ItemService(
            ItemRepository itemRepository,
            ItemDetailCache itemDetailCache,
            HotItemRankService hotItemRankService
    ) {
        this.itemRepository = itemRepository;
        this.itemDetailCache = itemDetailCache;
        this.hotItemRankService = hotItemRankService;
    }

    public ItemResponse create(Long sellerId, CreateItemRequest request) {
        Item item = new Item();
        item.setSellerId(sellerId);
        item.setTitle(request.title());
        item.setDescription(request.description());
        item.setCategory(request.category());
        item.setPrice(request.price());
        item.setConditionLevel(request.conditionLevel());
        item.setCampus(request.campus());
        item.setTradePlace(request.tradePlace());
        item.setImageUrls(request.imageUrls());
        item.setStatus(ItemStatus.ON_SALE);
        itemRepository.save(item);
        return ItemResponse.from(item);
    }

    public List<ItemResponse> list(ItemQuery query) {
        ItemStatus status = query.status() == null ? ItemStatus.ON_SALE : query.status();
        return itemRepository.findAll().stream()
                .filter(item -> item.getStatus() == status)
                .filter(item -> query.keyword() == null
                        || item.getTitle().contains(query.keyword())
                        || item.getDescription().contains(query.keyword()))
                .filter(item -> query.category() == null || item.getCategory().equals(query.category()))
                .filter(item -> query.campus() == null || item.getCampus().equals(query.campus()))
                .filter(item -> query.minPrice() == null || item.getPrice().compareTo(query.minPrice()) >= 0)
                .filter(item -> query.maxPrice() == null || item.getPrice().compareTo(query.maxPrice()) <= 0)
                .sorted(Comparator.comparing(Item::getCreatedAt).reversed())
                .map(ItemResponse::from)
                .toList();
    }

    public ItemResponse detail(Long itemId) {
        Optional<CachedItemDetail> cached = itemDetailCache.get(itemId);
        if (cached.isPresent()) {
            CachedItemDetail detail = cached.get();
            if (!detail.exists()) {
                throw BizException.notFound("商品不存在");
            }
            recordView(itemId);
            return detail.item();
        }

        Optional<Item> itemOptional = itemRepository.findById(itemId);
        if (itemOptional.isEmpty()) {
            itemDetailCache.putNull(itemId);
            throw BizException.notFound("商品不存在");
        }

        Item item = itemOptional.get();
        recordView(itemId);
        ItemResponse response = ItemResponse.from(item);
        itemDetailCache.putItem(response);
        return response;
    }

    public Item requireItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> BizException.notFound("商品不存在"));
    }

    public ItemResponse offShelf(Long sellerId, Long itemId) {
        Item item = requireItem(itemId);
        if (!item.getSellerId().equals(sellerId)) {
            throw BizException.forbidden("只能下架自己的商品");
        }
        if (item.getStatus() == ItemStatus.SOLD) {
            throw BizException.conflict("已售出的商品不能下架");
        }
        item.setStatus(ItemStatus.OFF_SHELF);
        itemRepository.save(item);
        evictItemCache(itemId);
        return ItemResponse.from(item);
    }

    public boolean reserveIfOnSale(Long itemId) {
        boolean updated = itemRepository.updateStatusIfCurrent(itemId, ItemStatus.ON_SALE, ItemStatus.RESERVED);
        if (updated) {
            evictItemCache(itemId);
        }
        return updated;
    }

    public void restoreOnSaleIfReserved(Long itemId) {
        boolean updated = itemRepository.updateStatusIfCurrent(itemId, ItemStatus.RESERVED, ItemStatus.ON_SALE);
        if (updated) {
            evictItemCache(itemId);
        }
    }

    public void markSoldIfReserved(Long itemId) {
        boolean updated = itemRepository.updateStatusIfCurrent(itemId, ItemStatus.RESERVED, ItemStatus.SOLD);
        if (!updated) {
            throw BizException.conflict("商品状态已变化，无法完成交易");
        }
        evictItemCache(itemId);
    }

    public List<ItemResponse> hotItems(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return hotItemRankService.topItemIds(safeLimit).stream()
                .map(itemRepository::findById)
                .flatMap(Optional::stream)
                .filter(item -> item.getStatus() == ItemStatus.ON_SALE)
                .map(ItemResponse::from)
                .toList();
    }

    private void recordView(Long itemId) {
        itemRepository.increaseViewCount(itemId);
        hotItemRankService.recordView(itemId);
    }

    private void evictItemCache(Long itemId) {
        itemDetailCache.evict(itemId);
        hotItemRankService.remove(itemId);
    }
}
