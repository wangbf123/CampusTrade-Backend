package com.campustrade.auth.security;

import com.campustrade.common.web.AuthenticatedUser;

import java.time.Instant;

public record TokenSession(AuthenticatedUser user, Instant expiresAt) {
}
