package com.campustrade.auth.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryTokenStore implements TokenStore {

    private final Map<String, TokenSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(String token, TokenSession session, Duration ttl) {
        sessions.put(token, session);
    }

    @Override
    public Optional<TokenSession> find(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    @Override
    public void delete(String token) {
        sessions.remove(token);
    }
}
