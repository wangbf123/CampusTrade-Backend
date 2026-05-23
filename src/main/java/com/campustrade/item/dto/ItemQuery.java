package com.campustrade.item.dto;

import com.campustrade.item.model.ItemStatus;

import java.math.BigDecimal;

public record ItemQuery(
        String keyword,
        String category,
        String campus,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        ItemStatus status
) {
}
