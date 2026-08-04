package com.campustrade.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@Profile("prod")
public class ProductionReadinessValidator implements ApplicationRunner {

    private final Environment environment;

    public ProductionReadinessValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    public void validate() {
        if (Boolean.FALSE.equals(environment.getProperty("app.production-readiness.enabled", Boolean.class, true))) {
            return;
        }

        List<String> errors = new ArrayList<>();
        requireFalse(errors, "app.demo.seed-data", "Production must not seed demo data");
        rejectExampleOrigins(errors);

        if (hasProfile("mysql")) {
            requireSecret(errors, "spring.datasource.password", "MySQL application password");
        }
        if (hasProfile("redis")) {
            requireSecret(errors, "spring.data.redis.password", "Redis password");
        }
        if (hasProfile("rabbitmq")) {
            requireSecret(errors, "spring.rabbitmq.password", "RabbitMQ password");
        }
        if ("s3".equalsIgnoreCase(environment.getProperty("app.storage.type", ""))) {
            requireSecret(errors, "app.storage.s3.access-key", "S3 access key");
            requireSecret(errors, "app.storage.s3.secret-key", "S3 secret key");
        }
        if (environment.getProperty("app.admin.bootstrap.enabled", Boolean.class, false)) {
            requireSecret(errors, "app.admin.bootstrap.password", "admin bootstrap password");
            String password = environment.getProperty("app.admin.bootstrap.password", "");
            if (password.length() < 12) {
                errors.add("admin bootstrap password must contain at least 12 characters");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Production readiness validation failed: " + String.join("; ", errors));
        }
    }

    private boolean hasProfile(String profile) {
        return environment.acceptsProfiles(Profiles.of(profile));
    }

    private void requireFalse(List<String> errors, String property, String message) {
        if (environment.getProperty(property, Boolean.class, false)) {
            errors.add(message + " (" + property + "=true)");
        }
    }

    private void requireSecret(List<String> errors, String property, String label) {
        String value = environment.getProperty(property, "");
        if (isUnsafeSecret(value)) {
            errors.add(label + " must be replaced with a strong non-placeholder value (" + property + ")");
        }
    }

    private boolean isUnsafeSecret(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 12) {
            return true;
        }
        return normalized.contains("change_me")
                || normalized.contains("changeme")
                || normalized.equals("password")
                || normalized.equals("password123")
                || normalized.equals("campus123")
                || normalized.equals("root")
                || normalized.equals("guest")
                || normalized.equals("admin")
                || normalized.startsWith("test");
    }

    private void rejectExampleOrigins(List<String> errors) {
        String origins = environment.getProperty("app.web.cors.allowed-origins", "");
        if (origins.toLowerCase(Locale.ROOT).contains("example.com")) {
            errors.add("CORS allowed origins must not contain example.com placeholders");
        }
    }
}
