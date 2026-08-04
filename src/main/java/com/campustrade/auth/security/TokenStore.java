package com.campustrade.auth.security;

import java.time.Duration;
import java.util.Optional;

public interface TokenStore {

    void save(String token, TokenSession session, Duration ttl);

    Optional<TokenSession> find(String token);

    void delete(String token);
}
