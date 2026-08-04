package com.campustrade.user.service;

import com.campustrade.auth.dto.LoginRequest;
import com.campustrade.auth.security.InMemoryTokenStore;
import com.campustrade.auth.security.PasswordHasher;
import com.campustrade.auth.security.TokenService;
import com.campustrade.registration.RegistrationProperties;
import com.campustrade.registration.repository.InMemoryInviteCodeRepository;
import com.campustrade.registration.service.InviteCodeService;
import com.campustrade.admin.repository.InMemoryAdminOperationLogRepository;
import com.campustrade.admin.service.AdminAuditService;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServicePasswordMigrationTest {

    @Test
    void loginShouldUpgradeLegacySha256PasswordHashToBcrypt() {
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        PasswordHasher passwordHasher = new PasswordHasher();
        UserService userService = new UserService(
                userRepository,
                passwordHasher,
                new TokenService(new InMemoryTokenStore(), 720),
                new InviteCodeService(
                        new RegistrationProperties(),
                        new InMemoryInviteCodeRepository(),
                        new AdminAuditService(new InMemoryAdminOperationLogRepository())
                )
        );

        User user = new User();
        user.setUsername("legacy-user");
        user.setPasswordHash(legacySha256("Pass123456"));
        user.setNickname("Legacy User");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.NORMAL);
        user.setCreditScore(100);
        userRepository.save(user);

        userService.login(new LoginRequest("legacy-user", "Pass123456"));

        User migrated = userRepository.findByUsername("legacy-user").orElseThrow();
        assertTrue(migrated.getPasswordHash().startsWith("$2"));
        assertTrue(passwordHasher.matches("Pass123456", migrated.getPasswordHash()));
        assertFalse(passwordHasher.needsRehash(migrated.getPasswordHash()));
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
