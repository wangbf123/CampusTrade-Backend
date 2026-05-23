package com.campustrade.item.repository;

import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class InMemoryItemRepository implements ItemRepository {

    private final AtomicLong idGenerator = new AtomicLong(2000);
    private final Map<Long, Item> items = new ConcurrentHashMap<>();

    @Override
    public synchronized Item save(Item item) {
        LocalDateTime now = LocalDateTime.now();
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
    public synchronized boolean updateStatusIfCurrent(Long itemId, ItemStatus expected, ItemStatus next) {
        Item item = items.get(itemId);
        if (item == null || item.getStatus() != expected) {
            return false;
        }
        item.setStatus(next);
        item.setVersion(item.getVersion() + 1);
        item.setUpdatedAt(LocalDateTime.now());
        return true;
    }

    @Override
    public synchronized void increaseViewCount(Long itemId) {
        Item item = items.get(itemId);
        if (item != null) {
            item.setViewCount(item.getViewCount() + 1);
            item.setUpdatedAt(LocalDateTime.now());
        }
    }
}
