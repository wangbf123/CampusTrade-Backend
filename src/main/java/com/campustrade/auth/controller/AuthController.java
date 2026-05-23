package com.campustrade.auth.controller;

import com.campustrade.auth.dto.AuthResponse;
import com.campustrade.auth.dto.LoginRequest;
import com.campustrade.auth.dto.RegisterRequest;
import com.campustrade.auth.security.TokenService;
import com.campustrade.common.web.ApiResponse;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import com.campustrade.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    public AuthController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    @RateLimit(key = "auth:register", permits = 5, windowSeconds = 60, scope = RateLimitScope.IP)
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(userService.register(request));
    }

    @PostMapping("/login")
    @RateLimit(key = "auth:login", permits = 10, windowSeconds = 60, scope = RateLimitScope.IP)
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(userService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        String token = authorization.startsWith("Bearer ") ? authorization.substring("Bearer ".length()) : authorization;
        tokenService.revoke(token);
        return ApiResponse.ok();
    }
}
