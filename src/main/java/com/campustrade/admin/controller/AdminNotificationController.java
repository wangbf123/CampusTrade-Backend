package com.campustrade.admin.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.notification.admin.NotificationRecoveryService;
import com.campustrade.notification.admin.NotificationReplayRequest;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {
    private final NotificationRecoveryService recovery;
    public AdminNotificationController(NotificationRecoveryService recovery) { this.recovery = recovery; }

    @GetMapping("/outbox")
    public ApiResponse<List<NotificationOutboxEvent>> list(@RequestParam(defaultValue = "FAILED") OutboxStatus status,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(recovery.list(CurrentUserContext.require(), status, limit));
    }

    @PostMapping("/outbox/{eventId}/replay")
    @RateLimit(key = "admin:notification:replay", permits = 20, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<NotificationOutboxEvent> replay(@PathVariable String eventId, @Valid @RequestBody NotificationReplayRequest request) {
        return ApiResponse.ok(recovery.replayFailed(CurrentUserContext.require(), eventId, request.reason()));
    }
}
