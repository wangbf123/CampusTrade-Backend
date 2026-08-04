package com.campustrade.admin;

import com.campustrade.auth.security.PasswordHasher;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminBootstrapInitializerTest {

    @Test
    void shouldCreateInitialAdminWhenEnabled() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setEnabled(true);
        properties.setUsername("root-admin");
        properties.setPassword("StrongPass123");
        properties.setNickname("Root Admin");
        InMemoryUserRepository userRepository = new InMemoryUserRepository();

        new AdminBootstrapInitializer(properties, userRepository, new PasswordHasher()).run();

        var admin = userRepository.findByUsername("root-admin").orElseThrow();
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertEquals("Root Admin", admin.getNickname());
        assertTrue(new PasswordHasher().matches("StrongPass123", admin.getPasswordHash()));
    }

    @Test
    void shouldRejectPlaceholderPasswordWhenEnabled() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setEnabled(true);
        properties.setUsername("root-admin");
        properties.setPassword("change_me_at_least_12_chars");

        assertThrows(IllegalStateException.class, () ->
                new AdminBootstrapInitializer(properties, new InMemoryUserRepository(), new PasswordHasher()).run());
    }
}
