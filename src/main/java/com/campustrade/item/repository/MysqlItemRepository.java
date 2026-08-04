package com.campustrade.item.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.item.dto.ItemQuery;
import com.campustrade.item.mapper.ItemImageMapper;
import com.campustrade.item.mapper.ItemMapper;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemImage;
import com.campustrade.item.model.ItemStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Profile("mysql")
public class MysqlItemRepository implements ItemRepository {

    private final ItemMapper itemMapper;
    private final ItemImageMapper itemImageMapper;

    public MysqlItemRepository(ItemMapper itemMapper, ItemImageMapper itemImageMapper) {
        this.itemMapper = itemMapper;
        this.itemImageMapper = itemImageMapper;
    }

    @Override
    @Transactional
    public Item save(Item item) {
        LocalDateTime now = LocalDateTime.now();
        if (item.getId() == null) {
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            itemMapper.insert(item);
            syncImages(item);
            return item;
        }

        item.setVersion(item.getVersion() + 1);
        item.setUpdatedAt(now);
        itemMapper.updateById(item);
        syncImages(item);
        return item;
    }

    @Override
    public Optional<Item> findById(Long id) {
        return Optional.ofNullable(itemMapper.selectById(id)).map(this::withImages);
    }

    @Override
    public List<Item> findAll() {
        return itemMapper.selectList(new LambdaQueryWrapper<Item>()
                        .orderByDesc(Item::getCreatedAt)
                        .orderByDesc(Item::getId))
                .stream()
                .map(this::withImages)
                .toList();
    }

    @Override
    public List<Item> search(ItemQuery query) {
        int offset = (query.page() - 1) * query.size();
        LambdaQueryWrapper<Item> wrapper = new LambdaQueryWrapper<Item>()
                .eq(Item::getStatus, query.status())
                .eq(query.category() != null, Item::getCategory, query.category())
                .eq(query.campus() != null, Item::getCampus, query.campus())
                .ge(query.minPrice() != null, Item::getPrice, query.minPrice())
                .le(query.maxPrice() != null, Item::getPrice, query.maxPrice())
                .and(query.keyword() != null, nested -> nested
                        .like(Item::getTitle, query.keyword())
                        .or()
                        .like(Item::getDescription, query.keyword()))
                .orderByDesc(Item::getCreatedAt)
                .orderByDesc(Item::getId)
                .last("LIMIT " + offset + ", " + query.size());
        return withImages(itemMapper.selectList(wrapper));
    }

    @Override
    public boolean updateStatusIfCurrent(Long itemId, ItemStatus expected, ItemStatus next) {
        int rows = itemMapper.update(null, new LambdaUpdateWrapper<Item>()
                .eq(Item::getId, itemId)
                .eq(Item::getStatus, expected)
                .set(Item::getStatus, next)
                .set(Item::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        return rows == 1;
    }

    @Override
    public void increaseViewCount(Long itemId) {
        itemMapper.update(null, new LambdaUpdateWrapper<Item>()
                .eq(Item::getId, itemId)
                .set(Item::getUpdatedAt, LocalDateTime.now())
                .setSql("view_count = view_count + 1"));
    }

    private Item withImages(Item item) {
        List<String> images = itemImageMapper.selectList(new LambdaQueryWrapper<ItemImage>()
                        .eq(ItemImage::getItemId, item.getId())
                        .orderByAsc(ItemImage::getSortOrder)
                        .orderByAsc(ItemImage::getId))
                .stream()
                .map(ItemImage::getImageUrl)
                .toList();
        item.setImageUrls(images);
        return item;
    }

    private List<Item> withImages(List<Item> items) {
        if (items.isEmpty()) {
            return items;
        }
        List<Long> itemIds = items.stream().map(Item::getId).toList();
        Map<Long, List<String>> imagesByItemId = itemImageMapper.selectList(new LambdaQueryWrapper<ItemImage>()
                        .in(ItemImage::getItemId, itemIds)
                        .orderByAsc(ItemImage::getItemId)
                        .orderByAsc(ItemImage::getSortOrder)
                        .orderByAsc(ItemImage::getId))
                .stream()
                .collect(Collectors.groupingBy(
                        ItemImage::getItemId,
                        LinkedHashMap::new,
                        Collectors.mapping(ItemImage::getImageUrl, Collectors.toList())
                ));
        items.forEach(item -> item.setImageUrls(imagesByItemId.getOrDefault(item.getId(), List.of())));
        return items;
    }

    private void syncImages(Item item) {
        itemImageMapper.delete(new LambdaQueryWrapper<ItemImage>().eq(ItemImage::getItemId, item.getId()));
        List<String> imageUrls = item.getImageUrls() == null ? List.of() : item.getImageUrls();
        for (int i = 0; i < imageUrls.size(); i++) {
            ItemImage image = new ItemImage();
            image.setItemId(item.getId());
            image.setImageUrl(imageUrls.get(i));
            image.setSortOrder(i);
            image.setCreatedAt(LocalDateTime.now());
            itemImageMapper.insert(image);
        }
    }
}
