package com.campustrade.common.web;

import com.campustrade.user.model.UserRole;

public record AuthenticatedUser(Long id, String username, UserRole role) {
}
