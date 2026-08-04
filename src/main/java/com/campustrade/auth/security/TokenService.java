package com.campustrade.auth.security;

import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.user.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class TokenService {

    private final SecureRandom secureRandom = new SecureRandom();
    private final TokenStore tokenStore;
    private final Duration tokenTtl;

    public TokenService(TokenStore tokenStore, @Value("${app.auth.token-ttl-minutes:720}") long tokenTtlMinutes) {
        this.tokenStore = tokenStore;
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
    }

    public String issue(User user) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        tokenStore.save(token, new TokenSession(
                new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()),
                Instant.now().plus(tokenTtl)
        ), tokenTtl);
        return token;
    }

    public Optional<AuthenticatedUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<TokenSession> sessionOptional = tokenStore.find(token);
        if (sessionOptional.isEmpty()) {
            return Optional.empty();
        }
        TokenSession session = sessionOptional.get();
        if (!session.expiresAt().isAfter(Instant.now())) {
            tokenStore.delete(token);
            return Optional.empty();
        }
        return Optional.of(session.user());
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.badRequest("token 不能为空");
        }
        tokenStore.delete(token);
    }
}
