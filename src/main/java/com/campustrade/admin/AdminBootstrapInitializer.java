package com.campustrade.admin;

import com.campustrade.auth.security.PasswordHasher;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapInitializer implements CommandLineRunner {

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public AdminBootstrapInitializer(
            AdminBootstrapProperties properties,
            UserRepository userRepository,
            PasswordHasher passwordHasher
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            return;
        }
        String username = normalize(properties.getUsername());
        String password = normalize(properties.getPassword());
        if (username == null || password == null) {
            throw new IllegalStateException("Admin bootstrap requires username and password");
        }
        if (password.length() < 12) {
            throw new IllegalStateException("Admin bootstrap password must be at least 12 characters");
        }
        if (password.toLowerCase().contains("change_me")) {
            throw new IllegalStateException("Admin bootstrap password must not use the placeholder value");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPasswordHash(passwordHasher.hash(password));
        admin.setNickname(normalize(properties.getNickname()) == null ? username : properties.getNickname().trim());
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.NORMAL);
        admin.setCreditScore(100);
        userRepository.save(admin);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
