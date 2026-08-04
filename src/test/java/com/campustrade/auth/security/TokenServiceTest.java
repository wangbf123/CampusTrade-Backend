package com.campustrade.auth.security;

import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenServiceTest {

    @Test
    void issueShouldPersistResolvableSession() {
        TokenService tokenService = new TokenService(new InMemoryTokenStore(), 720);
        User user = user();

        String token = tokenService.issue(user);

        Optional<AuthenticatedUser> resolved = tokenService.resolve(token);
        assertTrue(resolved.isPresent());
        assertEquals(user.getId(), resolved.get().id());
        assertEquals(user.getUsername(), resolved.get().username());
        assertEquals(user.getRole(), resolved.get().role());
    }

    @Test
    void issueShouldGenerateDifferentTokensForSameUser() {
        TokenService tokenService = new TokenService(new InMemoryTokenStore(), 720);
        User user = user();

        assertNotEquals(tokenService.issue(user), tokenService.issue(user));
    }

    @Test
    void revokeShouldDeleteSession() {
        TokenService tokenService = new TokenService(new InMemoryTokenStore(), 720);
        String token = tokenService.issue(user());

        tokenService.revoke(token);

        assertTrue(tokenService.resolve(token).isEmpty());
    }

    @Test
    void resolveShouldDeleteExpiredSession() {
        InMemoryTokenStore tokenStore = new InMemoryTokenStore();
        TokenService tokenService = new TokenService(tokenStore, 0);
        String token = tokenService.issue(user());

        assertTrue(tokenService.resolve(token).isEmpty());
        assertTrue(tokenStore.find(token).isEmpty());
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(UserRole.USER);
        return user;
    }
}
