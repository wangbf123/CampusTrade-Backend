package com.campustrade.item.dto;

import com.campustrade.item.model.ConditionLevel;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ItemResponse(
        Long id,
        Long sellerId,
        String title,
        String description,
        String category,
        BigDecimal price,
        ConditionLevel conditionLevel,
        String campus,
        String tradePlace,
        ItemStatus status,
        long viewCount,
        long favoriteCount,
        long version,
        List<String> imageUrls,
        LocalDateTime createdAt
) {
    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getSellerId(),
                item.getTitle(),
                item.getDescription(),
                item.getCategory(),
                item.getPrice(),
                item.getConditionLevel(),
                item.getCampus(),
                item.getTradePlace(),
                item.getStatus(),
                item.getViewCount(),
                item.getFavoriteCount(),
                item.getVersion(),
                List.copyOf(item.getImageUrls()),
                item.getCreatedAt()
        );
    }
}
