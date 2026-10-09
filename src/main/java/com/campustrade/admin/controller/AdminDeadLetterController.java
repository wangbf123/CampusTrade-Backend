package com.campustrade.admin.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.notification.admin.DeadLetterReplayRequest;
import com.campustrade.notification.admin.RabbitDeadLetterRecoveryService;
import com.campustrade.notification.model.NotificationOutboxEvent;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@Profile("rabbitmq")
@RequestMapping("/api/admin/notifications/dead-letters")
public class AdminDeadLetterController {
    private final RabbitDeadLetterRecoveryService recovery;
    public AdminDeadLetterController(RabbitDeadLetterRecoveryService recovery) { this.recovery = recovery; }

    @GetMapping("/head")
    public ApiResponse<Optional<RabbitDeadLetterRecoveryService.DeadLetterHead>> head() {
        return ApiResponse.ok(recovery.peek(CurrentUserContext.require()));
    }

    @PostMapping("/replay")
    @RateLimit(key = "admin:notification:dlq:replay", permits = 20, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<NotificationOutboxEvent> replay(@Valid @RequestBody DeadLetterReplayRequest request) {
        return ApiResponse.ok(recovery.replay(CurrentUserContext.require(), request));
    }
}
