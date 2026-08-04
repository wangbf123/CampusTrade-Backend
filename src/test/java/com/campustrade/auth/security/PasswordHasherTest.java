package com.campustrade.auth.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void hashShouldUseBcryptWithPerPasswordSalt() {
        String firstHash = passwordHasher.hash("Pass123456");
        String secondHash = passwordHasher.hash("Pass123456");

        assertTrue(firstHash.startsWith("$2"));
        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordHasher.matches("Pass123456", firstHash));
        assertFalse(passwordHasher.matches("WrongPass", firstHash));
        assertFalse(passwordHasher.needsRehash(firstHash));
    }

    @Test
    void matchesShouldAcceptLegacySha256HashForMigration() {
        String legacyHash = legacySha256("Pass123456");

        assertTrue(passwordHasher.matches("Pass123456", legacyHash));
        assertFalse(passwordHasher.matches("WrongPass", legacyHash));
        assertTrue(passwordHasher.needsRehash(legacyHash));
    }

    private String legacySha256(String rawPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(("campus-trade:" + rawPassword).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
