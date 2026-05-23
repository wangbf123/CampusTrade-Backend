package com.campustrade.item.repository;

import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;

import java.util.List;
import java.util.Optional;

public interface ItemRepository {

    Item save(Item item);

    Optional<Item> findById(Long id);

    List<Item> findAll();

    boolean updateStatusIfCurrent(Long itemId, ItemStatus expected, ItemStatus next);

    void increaseViewCount(Long itemId);
}
