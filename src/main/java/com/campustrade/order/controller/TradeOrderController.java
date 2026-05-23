package com.campustrade.order.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.order.dto.CancelOrderRequest;
import com.campustrade.order.dto.CreateAppointmentRequest;
import com.campustrade.order.dto.OrderResponse;
import com.campustrade.order.service.TradeOrderService;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TradeOrderController {

    private final TradeOrderService tradeOrderService;

    public TradeOrderController(TradeOrderService tradeOrderService) {
        this.tradeOrderService = tradeOrderService;
    }

    @PostMapping("/items/{itemId}/appointments")
    @RateLimit(key = "appointment:create", permits = 10, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<OrderResponse> createAppointment(
            @PathVariable Long itemId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAppointmentRequest request
    ) {
        return ApiResponse.ok(tradeOrderService.createAppointment(CurrentUserContext.require(), itemId, request, idempotencyKey));
    }

    @PostMapping("/orders/{orderId}/confirm")
    @RateLimit(key = "order:confirm", permits = 30, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<OrderResponse> confirm(@PathVariable Long orderId) {
        return ApiResponse.ok(tradeOrderService.confirm(CurrentUserContext.require(), orderId));
    }

    @PostMapping("/orders/{orderId}/reject")
    @RateLimit(key = "order:reject", permits = 30, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<OrderResponse> reject(@PathVariable Long orderId) {
        return ApiResponse.ok(tradeOrderService.reject(CurrentUserContext.require(), orderId));
    }

    @PostMapping("/orders/{orderId}/cancel")
    @RateLimit(key = "order:cancel", permits = 30, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<OrderResponse> cancel(
            @PathVariable Long orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request
    ) {
        CancelOrderRequest safeRequest = request == null ? new CancelOrderRequest(null) : request;
        return ApiResponse.ok(tradeOrderService.cancel(CurrentUserContext.require(), orderId, safeRequest));
    }

    @PostMapping("/orders/{orderId}/complete")
    @RateLimit(key = "order:complete", permits = 30, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<OrderResponse> complete(@PathVariable Long orderId) {
        return ApiResponse.ok(tradeOrderService.complete(CurrentUserContext.require(), orderId));
    }

    @GetMapping("/orders/my-buy")
    public ApiResponse<List<OrderResponse>> myBuyOrders() {
        AuthenticatedUser user = CurrentUserContext.require();
        return ApiResponse.ok(tradeOrderService.myBuyOrders(user.id()));
    }

    @GetMapping("/orders/my-sell")
    public ApiResponse<List<OrderResponse>> mySellOrders() {
        AuthenticatedUser user = CurrentUserContext.require();
        return ApiResponse.ok(tradeOrderService.mySellOrders(user.id()));
    }
}
