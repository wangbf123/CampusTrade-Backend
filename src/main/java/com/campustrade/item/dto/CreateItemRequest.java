package com.campustrade.item.dto;

import com.campustrade.item.model.ConditionLevel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record CreateItemRequest(
        @NotBlank @Size(max = 64) String title,
        @NotBlank @Size(max = 1000) String description,
        @NotBlank @Size(max = 32) String category,
        @NotNull @DecimalMin("0.01") BigDecimal price,
        @NotNull ConditionLevel conditionLevel,
        @NotBlank @Size(max = 64) String campus,
        @NotBlank @Size(max = 128) String tradePlace,
        List<String> imageUrls
) {
}
