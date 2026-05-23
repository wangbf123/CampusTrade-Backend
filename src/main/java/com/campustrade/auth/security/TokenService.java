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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration tokenTtl;

    public TokenService(@Value("${app.auth.token-ttl-minutes:720}") long tokenTtlMinutes) {
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
    }

    public String issue(User user) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        sessions.put(token, new Session(
                new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()),
                Instant.now().plus(tokenTtl)
        ));
        return token;
    }

    public Optional<AuthenticatedUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.user());
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.badRequest("token 不能为空");
        }
        sessions.remove(token);
    }

    private record Session(AuthenticatedUser user, Instant expiresAt) {
    }
}
