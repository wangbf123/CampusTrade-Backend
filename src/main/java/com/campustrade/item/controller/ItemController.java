package com.campustrade.item.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.item.dto.CreateItemRequest;
import com.campustrade.item.dto.ItemQuery;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.service.ItemService;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @PostMapping
    @RateLimit(key = "item:create", permits = 20, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<ItemResponse> create(@Valid @RequestBody CreateItemRequest request) {
        return ApiResponse.ok(itemService.create(CurrentUserContext.require().id(), request));
    }

    @GetMapping
    public ApiResponse<List<ItemResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String campus,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) ItemStatus status
    ) {
        return ApiResponse.ok(itemService.list(new ItemQuery(keyword, category, campus, minPrice, maxPrice, status)));
    }

    @GetMapping("/hot")
    public ApiResponse<List<ItemResponse>> hotItems(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(itemService.hotItems(limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ItemResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(itemService.detail(id));
    }

    @PutMapping("/{id}/off-shelf")
    @RateLimit(key = "item:off-shelf", permits = 30, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<ItemResponse> offShelf(@PathVariable Long id) {
        return ApiResponse.ok(itemService.offShelf(CurrentUserContext.require().id(), id));
    }
}
