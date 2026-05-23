package com.campustrade.user.dto;

import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;

public record UserResponse(
        Long id,
        String username,
        String nickname,
        String phone,
        String campus,
        UserRole role,
        UserStatus status,
        int creditScore
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getPhone(),
                user.getCampus(),
                user.getRole(),
                user.getStatus(),
                user.getCreditScore()
        );
    }
}
