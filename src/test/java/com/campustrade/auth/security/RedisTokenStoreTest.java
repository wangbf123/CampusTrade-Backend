package com.campustrade.auth.security;

import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

    @Test
    void saveAndFindShouldRoundTripSessionThroughJsonPayload() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisTokenStore tokenStore = new RedisTokenStore(redisTemplate, new ObjectMapper().findAndRegisterModules());
        TokenSession session = new TokenSession(
                new AuthenticatedUser(1L, "alice", UserRole.USER),
                Instant.now().plusSeconds(60)
        );
        Duration ttl = Duration.ofMinutes(30);

        tokenStore.save("plain-token", session, ttl);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), payloadCaptor.capture(), eq(ttl));

        String redisKey = keyCaptor.getValue();
        assertTrue(redisKey.startsWith("auth:session:"));
        assertFalse(redisKey.contains("plain-token"));

        when(valueOperations.get(redisKey)).thenReturn(payloadCaptor.getValue());
        Optional<TokenSession> found = tokenStore.find("plain-token");

        assertTrue(found.isPresent());
        assertEquals(session.user(), found.get().user());
        assertEquals(session.expiresAt(), found.get().expiresAt());
    }

    @Test
    void deleteShouldRemoveHashedTokenKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisTokenStore tokenStore = new RedisTokenStore(redisTemplate, new ObjectMapper().findAndRegisterModules());

        tokenStore.delete("plain-token");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).delete(keyCaptor.capture());
        assertTrue(keyCaptor.getValue().startsWith("auth:session:"));
        assertFalse(keyCaptor.getValue().contains("plain-token"));
    }
}
