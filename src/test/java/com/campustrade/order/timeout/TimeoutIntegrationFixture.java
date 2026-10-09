package com.campustrade.order.timeout;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.campustrade.item.service.ItemService;
import com.campustrade.notification.mapper.NotificationOutboxMapper;
import com.campustrade.notification.repository.MysqlNotificationOutboxRepository;
import com.campustrade.notification.service.NotificationOutboxService;
import com.campustrade.observability.OrderTimeoutMetrics;
import com.campustrade.order.mapper.TradeOrderMapper;
import com.campustrade.order.repository.MysqlTradeOrderRepository;
import com.campustrade.order.service.OrderStateMachine;
import com.campustrade.order.service.TradeOrderService;
import com.campustrade.risk.idempotency.AppointmentIdempotencyRepository;
import com.campustrade.risk.idempotency.IdempotencyService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.mockito.Mockito.mock;

/** Shared by the parent test and SIGKILL workers; no application scheduler runs in these fixtures. */
final class TimeoutIntegrationFixture implements AutoCloseable {
    final HikariDataSource dataSource;
    final JdbcTemplate jdbc;
    final MysqlTradeOrderRepository orders;
    final MysqlNotificationOutboxRepository outbox;
    final StringRedisTemplate redis;
    final String key;
    final OrderTimeoutMetrics metrics = new OrderTimeoutMetrics();
    private final LettuceConnectionFactory connections;
    private final DataSourceTransactionManager transactions;

    TimeoutIntegrationFixture(String database, String key) throws Exception {
        this.key = key;
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(databaseUrl(database));
        configuration.setUsername(username());
        configuration.setPassword(password());
        configuration.setMaximumPoolSize(4);
        configuration.setConnectionInitSql("SET time_zone = '+00:00'");
        configuration.setMinimumIdle(0);
        configuration.setPoolName("campus-timeout-it");
        dataSource = new HikariDataSource(configuration);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(TradeOrderMapper.class);
        mybatis.addMapper(NotificationOutboxMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(mybatis);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        orders = new MysqlTradeOrderRepository(sessions.getMapper(TradeOrderMapper.class));
        outbox = new MysqlNotificationOutboxRepository(sessions.getMapper(NotificationOutboxMapper.class));
        connections = new LettuceConnectionFactory(redisHost(), redisPort());
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    TradeOrderService service(OrderTimeoutQueue queue) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("transactionManager", transactions);
        return new TradeOrderService(orders, mock(ItemService.class), new NotificationOutboxService(outbox),
                new OrderStateMachine(), queue, mock(IdempotencyService.class), mock(AppointmentIdempotencyRepository.class),
                factory.getBeanProvider(PlatformTransactionManager.class), 24, 50, 1, 2, metrics);
    }

    RedisOrderTimeoutQueue queue() { return new RedisOrderTimeoutQueue(redis, key); }

    void clearQueue() { redis.delete(List.of(key, key + ":processing", key + ":tokens")); }

    static String adminUrl() {
        return System.getenv().getOrDefault("CAMPUS_MYSQL_IT_ADMIN_URL",
                "jdbc:mysql://127.0.0.1:3307/?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
    }
    static String username() { return System.getenv().getOrDefault("CAMPUS_MYSQL_IT_USER", "root"); }
    static String password() {
        String value = System.getenv("CAMPUS_MYSQL_IT_PASSWORD");
        if (value == null) {
            throw new IllegalStateException("Set CAMPUS_MYSQL_IT_PASSWORD securely before enabling integration tests");
        }
        return value;
    }
    static String databaseUrl(String database) {
        String url = adminUrl();
        int slash = url.indexOf('/', "jdbc:mysql://".length());
        int question = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + database + (question < 0 ? "" : url.substring(question));
    }
    static String redisHost() { return System.getenv().getOrDefault("CAMPUS_REDIS_IT_HOST", "127.0.0.1"); }
    static int redisPort() { return Integer.parseInt(System.getenv().getOrDefault("CAMPUS_REDIS_IT_PORT", "6379")); }

    @Override
    public void close() {
        connections.destroy();
        dataSource.close();
    }
}
