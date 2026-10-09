package com.campustrade.item.repository;

import com.campustrade.item.dto.ItemQuery;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryItemRepository implements ItemRepository {

    private final AtomicLong idGenerator = new AtomicLong(2000);
    private final Map<Long, Item> items = new ConcurrentHashMap<>();

    @Override
    public synchronized Item save(Item item) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (item.getId() == null) {
            item.setId(idGenerator.incrementAndGet());
            item.setCreatedAt(now);
        }
        item.setVersion(item.getVersion() + 1);
        item.setUpdatedAt(now);
        items.put(item.getId(), item);
        return item;
    }

    @Override
    public Optional<Item> findById(Long id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public List<Item> findAll() {
        return new ArrayList<>(items.values());
    }

    @Override
    public List<Item> search(ItemQuery query) {
        int offset = (query.page() - 1) * query.size();
        return items.values().stream()
                .filter(item -> item.getStatus() == query.status())
                .filter(item -> query.keyword() == null
                        || item.getTitle().contains(query.keyword())
                        || item.getDescription().contains(query.keyword()))
                .filter(item -> query.category() == null || item.getCategory().equals(query.category()))
                .filter(item -> query.campus() == null || item.getCampus().equals(query.campus()))
                .filter(item -> query.minPrice() == null || item.getPrice().compareTo(query.minPrice()) >= 0)
                .filter(item -> query.maxPrice() == null || item.getPrice().compareTo(query.maxPrice()) <= 0)
                .sorted(Comparator.comparing(Item::getCreatedAt)
                        .thenComparing(Item::getId)
                        .reversed())
                .skip(offset)
                .limit(query.size())
                .toList();
    }

    @Override
    public synchronized boolean updateStatusIfCurrent(Long itemId, ItemStatus expected, ItemStatus next) {
        Item item = items.get(itemId);
        if (item == null || item.getStatus() != expected) {
            return false;
        }
        item.setStatus(next);
        item.setVersion(item.getVersion() + 1);
        item.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }

    @Override
    public synchronized void increaseViewCount(Long itemId) {
        Item item = items.get(itemId);
        if (item != null) {
            item.setViewCount(item.getViewCount() + 1);
            item.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    @Override
    public synchronized boolean reserveIfOnSale(Long itemId, Long orderId) {
        Item item = items.get(itemId);
        if (orderId == null || item == null || item.getStatus() != ItemStatus.ON_SALE
                || item.getReservedOrderId() != null) {
            return false;
        }
        item.setStatus(ItemStatus.RESERVED);
        item.setReservedOrderId(orderId);
        item.setVersion(item.getVersion() + 1);
        item.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }

    @Override
    public synchronized boolean releaseReservation(Long itemId, Long orderId) {
        return transitionReservation(itemId, orderId, ItemStatus.ON_SALE);
    }

    @Override
    public synchronized boolean sellReservation(Long itemId, Long orderId) {
        return transitionReservation(itemId, orderId, ItemStatus.SOLD);
    }

    private boolean transitionReservation(Long itemId, Long orderId, ItemStatus next) {
        Item item = items.get(itemId);
        if (orderId == null || item == null || item.getStatus() != ItemStatus.RESERVED
                || !orderId.equals(item.getReservedOrderId())) {
            return false;
        }
        item.setStatus(next);
        item.setReservedOrderId(null);
        item.setVersion(item.getVersion() + 1);
        item.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }
}
