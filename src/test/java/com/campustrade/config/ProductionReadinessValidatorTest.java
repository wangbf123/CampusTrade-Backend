package com.campustrade.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionReadinessValidatorTest {

    @Test
    void shouldAcceptSafeProductionConfiguration() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("spring.datasource.password", "MysqlPassword12345!")
                .withProperty("spring.data.redis.password", "RedisPassword12345!")
                .withProperty("spring.rabbitmq.password", "RabbitPassword12345!")
                .withProperty("app.web.cors.allowed-origins", "https://trade.jlu.example");

        assertDoesNotThrow(() -> new ProductionReadinessValidator(environment).validate());
    }

    @Test
    void shouldRejectPlaceholderSecretsAndExampleCorsOrigins() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("spring.datasource.password", "change_me")
                .withProperty("spring.data.redis.password", "")
                .withProperty("spring.rabbitmq.password", "guest")
                .withProperty("app.web.cors.allowed-origins", "https://www.example.com");

        assertThrows(IllegalStateException.class,
                () -> new ProductionReadinessValidator(environment).validate());
    }

    @Test
    void shouldRejectDemoSeedDataInProduction() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("app.demo.seed-data", "true")
                .withProperty("spring.datasource.password", "MysqlPassword12345!")
                .withProperty("spring.data.redis.password", "RedisPassword12345!")
                .withProperty("spring.rabbitmq.password", "RabbitPassword12345!");

        assertThrows(IllegalStateException.class,
                () -> new ProductionReadinessValidator(environment).validate());
    }

    @Test
    void shouldAllowExplicitDisableForEmergencyLocalSmoke() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("app.production-readiness.enabled", "false")
                .withProperty("spring.datasource.password", "change_me")
                .withProperty("spring.data.redis.password", "")
                .withProperty("spring.rabbitmq.password", "guest")
                .withProperty("app.web.cors.allowed-origins", "https://www.example.com");

        assertDoesNotThrow(() -> new ProductionReadinessValidator(environment).validate());
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.demo.seed-data", "false")
                .withProperty("app.production-readiness.enabled", "true");
        environment.setActiveProfiles("prod", "mysql", "redis", "rabbitmq");
        return environment;
    }
}
