package com.campustrade.order.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeUtcConfigurationTest {
    @Test
    void mysqlConnectionsUseUtcForJdbcInterpretationAndDatabaseDeadlineChecks() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-mysql.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties);
        assertTrue(properties.getProperty("spring.datasource.url").contains("serverTimezone=UTC"));
        assertEquals("SET time_zone = '+00:00'",
                properties.getProperty("spring.datasource.hikari.connection-init-sql"));
    }
}
