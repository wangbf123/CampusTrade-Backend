package com.campustrade.auth.dto;

import com.campustrade.user.dto.UserResponse;

public record AuthResponse(String token, UserResponse user) {
}
